"""FastAPI 모델 서버 (내부망 전용, Spring만 호출).

MLflow 레지스트리의 champion alias 버전을 서빙한다. alias가 바뀌면 다시 불러오고,
불러오기에 실패하면 이전 버전으로 계속 서빙한다. MLflow에 붙지 못하면 ml/artifacts/*.joblib을 쓴다.

    uvicorn solarcast.serve:app --port 8000
"""
import logging
import os
import threading
import time
from datetime import date, datetime
from typing import Literal

import joblib
import pandas as pd
from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field

from .config import PLANT
from .features import day_target_times, demand_features, issued_at_for, solar_features
from .model import ForecastBundle
from .train import ARTIFACT_DIR, MODEL_NAMES

log = logging.getLogger("model-api")
ALIAS = os.getenv("MODEL_ALIAS", "champion")
RELOAD_INTERVAL = int(os.getenv("MODEL_RELOAD_SECONDS", "60"))

PREDICT_LATENCY = Histogram("predict_latency_seconds", "predict latency", ["target"])
PREDICT_ERRORS = Counter("predict_errors_total", "predict errors", ["target"])


class LoadedModel:
    def __init__(self, bundle: ForecastBundle, version: str, source: str):
        self.bundle, self.version, self.source = bundle, version, source
        self.loaded_at = datetime.now().isoformat(timespec="seconds")


class Registry:
    def __init__(self):
        self.models: dict[str, LoadedModel] = {}
        self.lock = threading.Lock()
        self.client = None
        uri = os.getenv("MLFLOW_TRACKING_URI")
        if uri:
            import mlflow
            from mlflow import MlflowClient
            mlflow.set_tracking_uri(uri)
            self.client = MlflowClient()

    def _load_from_mlflow(self, target) -> LoadedModel:
        import mlflow.pyfunc
        name = MODEL_NAMES[target]
        mv = self.client.get_model_version_by_alias(name, ALIAS)
        current = self.models.get(target)
        if current and current.version == str(mv.version) and current.source == "mlflow":
            return current
        pyfunc = mlflow.pyfunc.load_model(f"models:/{name}@{ALIAS}")
        bundle = pyfunc.unwrap_python_model().bundle
        log.info("loaded %s v%s from mlflow", name, mv.version)
        return LoadedModel(bundle, str(mv.version), "mlflow")

    def _load_local(self, target) -> LoadedModel:
        bundle = joblib.load(ARTIFACT_DIR / f"{target}.joblib")
        return LoadedModel(bundle, str(bundle.meta.get("version", "local")), "local")

    def refresh(self):
        for target in MODEL_NAMES:
            try:
                loaded = self._load_from_mlflow(target) if self.client else self._load_local(target)
            except Exception as e:  # 실패하면 기존 모델 유지
                log.warning("model load failed for %s: %s", target, e)
                if target in self.models:
                    continue
                try:
                    loaded = self._load_local(target)
                except Exception as e2:
                    log.error("no model for %s: %s", target, e2)
                    continue
            with self.lock:
                self.models[target] = loaded

    def get(self, target) -> LoadedModel:
        with self.lock:
            m = self.models.get(target)
        if m is None:
            raise HTTPException(503, f"{target} 모델이 로드되지 않았습니다")
        return m

    def versions(self):
        if not self.client:
            return {t: [{"version": m.version, "aliases": ["champion"], "metrics": m.bundle.meta.get("metrics", {})}]
                    for t, m in self.models.items()}
        out = {}
        for target, name in MODEL_NAMES.items():
            rows = []
            for found in self.client.search_model_versions(f"name='{name}'"):
                # search 결과에는 alias가 빠져 있어 버전별로 다시 조회한다
                mv = self.client.get_model_version(name, found.version)
                run = self.client.get_run(mv.run_id)
                rows.append({"version": str(mv.version), "aliases": list(mv.aliases), "run_id": mv.run_id,
                             "created_at": mv.creation_timestamp, "metrics": run.data.metrics,
                             "params": {k: v for k, v in run.data.params.items() if k != "features"}})
            out[target] = sorted(rows, key=lambda r: int(r["version"]), reverse=True)
        return out


registry = Registry()
app = FastAPI(title="solar-demand model-api", version="1.0")


def _reload_loop():
    while True:
        time.sleep(RELOAD_INTERVAL)
        registry.refresh()


@app.on_event("startup")
def startup():
    registry.refresh()
    threading.Thread(target=_reload_loop, daemon=True).start()


class WeatherRow(BaseModel):
    fcst_time: datetime
    tmp: float
    reh: float
    sky: int | None = None
    pty: int | None = 0
    wsd: float | None = None


class HistoryRow(BaseModel):
    time: datetime
    value: float


class PredictRequest(BaseModel):
    target: Literal["solar", "demand"]
    target_date: date
    weather: list[WeatherRow] = Field(min_length=24)
    history: list[HistoryRow] = []
    issued_at: datetime | None = None


class HourPrediction(BaseModel):
    target_time: datetime
    p10: float
    p50: float
    p90: float


class PredictResponse(BaseModel):
    target: str
    model_version: str
    model_source: str
    issued_at: datetime
    predictions: list[HourPrediction]


def build_features(req: PredictRequest) -> pd.DataFrame:
    times = day_target_times(pd.Timestamp(req.target_date))
    w = pd.DataFrame([r.model_dump() for r in req.weather]).rename(columns={"fcst_time": "target_time"})
    w["target_time"] = pd.to_datetime(w["target_time"]).dt.tz_localize(None)
    w = w[w["target_time"].isin(times)]
    issued_at = pd.Timestamp(req.issued_at).tz_localize(None) if req.issued_at else issued_at_for(req.target_date)

    if req.target == "solar":
        w = w.groupby("target_time", as_index=False).first()
        if len(w) != 24 or w[["sky", "wsd"]].isna().any().any():
            raise HTTPException(422, "태양광 예측에는 24시간 기상(sky, wsd 포함)이 필요합니다")
        return solar_features(w[["target_time", "tmp", "reh", "sky", "pty", "wsd"]], PLANT.lat, PLANT.lon)

    if w["target_time"].nunique() != 24:
        raise HTTPException(422, "수요 예측에는 24시간 기상이 필요합니다")
    if not req.history:
        raise HTTPException(422, "수요 예측에는 최근 실측(history)이 필요합니다")
    hist = pd.Series({pd.Timestamp(h.time).tz_localize(None): h.value for h in req.history}).sort_index()
    X = demand_features(w[["target_time", "tmp", "reh"]], hist, issued_at)
    if X[["lag_7d", "week_mean"]].isna().any().any():
        raise HTTPException(422, "history가 부족합니다 (D-8 ~ D-1 필요)")
    return X


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest):
    with PREDICT_LATENCY.labels(req.target).time():
        model = registry.get(req.target)
        try:
            X = build_features(req)
            pred = model.bundle.predict(X)
        except HTTPException:
            PREDICT_ERRORS.labels(req.target).inc()
            raise
        except Exception as e:
            PREDICT_ERRORS.labels(req.target).inc()
            log.exception("predict failed")
            raise HTTPException(500, f"예측 실패: {e}")
    rows = [HourPrediction(target_time=t, p10=round(a, 2), p50=round(b, 2), p90=round(c, 2))
            for t, a, b, c in zip(X["target_time"], pred["p10"], pred["p50"], pred["p90"])]
    issued = req.issued_at or issued_at_for(req.target_date).to_pydatetime()
    return PredictResponse(target=req.target, model_version=model.version, model_source=model.source,
                           issued_at=issued, predictions=rows)


@app.get("/health")
def health():
    models = {t: {"version": m.version, "source": m.source, "loaded_at": m.loaded_at}
              for t, m in registry.models.items()}
    ok = all(t in models for t in MODEL_NAMES)
    return {"status": "UP" if ok else "DEGRADED", "alias": ALIAS, "models": models}


@app.get("/models")
def models():
    return registry.versions()


@app.post("/reload")
def reload():
    registry.refresh()
    return health()


def _require_registry():
    if not registry.client:
        raise HTTPException(409, "MLflow 레지스트리 없이 실행 중이라 모델 교체를 할 수 없습니다")


@app.post("/models/{target}/promote")
def promote_challenger(target: Literal["solar", "demand"], days: int = 30, apply: bool = True):
    _require_registry()
    from . import promote as p
    try:
        result = p.promote(target, days) if apply else p.compare(target, days)
    except Exception as e:
        raise HTTPException(409, f"비교 불가: {e}")
    registry.refresh()
    return result


_retrain_state: dict[str, dict] = {}


def _retrain(target):
    from . import train
    _retrain_state[target] = {"status": "running", "started_at": datetime.now().isoformat(timespec="seconds")}
    try:
        results, best = train.run_solar() if target == "solar" else train.run_demand()
        _retrain_state[target].update(status="done", best=best, finished_at=datetime.now().isoformat(timespec="seconds"))
    except Exception as e:
        log.exception("retrain failed")
        _retrain_state[target].update(status="failed", error=str(e))


@app.post("/models/{target}/retrain")
def retrain(target: Literal["solar", "demand"]):
    """재학습 결과는 바로 서빙하지 않고 challenger로 등록된다 (champion이 있으면)."""
    _require_registry()
    if _retrain_state.get(target, {}).get("status") == "running":
        return _retrain_state[target]
    threading.Thread(target=_retrain, args=(target,), daemon=True).start()
    return {"status": "started", "target": target}


@app.get("/models/{target}/retrain")
def retrain_status(target: Literal["solar", "demand"]):
    return _retrain_state.get(target, {"status": "idle"})


@app.post("/models/{target}/rollback")
def rollback(target: Literal["solar", "demand"], version: str | None = None):
    _require_registry()
    from . import promote as p
    try:
        result = p.rollback(target, version)
    except Exception as e:
        raise HTTPException(409, f"롤백 불가: {e}")
    registry.refresh()
    return result


@app.get("/metrics", response_class=PlainTextResponse)
def metrics():
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)
