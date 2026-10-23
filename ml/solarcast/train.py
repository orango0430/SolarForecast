"""학습 파이프라인: 베이스라인 -> Ridge -> LightGBM(분위수) 실험을 MLflow에 기록하고 모델을 등록한다.

검증: 시간순 분할. 마지막 REPLAY_DAYS일은 테스트(리플레이) 구간으로 학습에 쓰지 않는다.
테스트 입력은 운영과 같게 '예보' 기상으로 만든다.

    python -m solarcast.train --target solar
    python -m solarcast.train --target demand
"""
import argparse
import json
import os
from pathlib import Path

import joblib
import mlflow
import numpy as np
import pandas as pd
from mlflow import MlflowClient
from sklearn.model_selection import TimeSeriesSplit

from .config import PLANT, REPLAY_DAYS, ROOT
from .dataset import demand_training_frame, solar_training_frame
from .features import DEMAND_FEATURES, SOLAR_FEATURES, SOLAR_FEATURES_UPPER
from .model import (QUANTILES, ForecastBundle, PyfuncWrapper, coverage, demand_metrics, fit_lgbm,
                    fit_ridge, make_target, solar_metrics)

ARTIFACT_DIR = ROOT / "ml" / "artifacts"
MODEL_NAMES = {"solar": "solar-forecast", "demand": "demand-forecast"}


def setup_mlflow():
    uri = os.getenv("MLFLOW_TRACKING_URI", f"sqlite:///{(ROOT / 'ml' / 'mlflow.db').as_posix()}")
    mlflow.set_tracking_uri(uri)
    return uri


def time_split(df: pd.DataFrame, replay_days=REPLAY_DAYS):
    test_start = df["target_time"].max().normalize() - pd.Timedelta(days=replay_days - 1)
    return df[df["target_time"] <= test_start], df[df["target_time"] > test_start], test_start


def same_hour_mean(series: pd.Series, times: pd.Series, days=range(2, 9)) -> np.ndarray:
    """베이스라인: 2~8일 전 같은 시각 평균 (예측 시점에 아는 값만)."""
    t = pd.DatetimeIndex(times)
    return np.nanmean(np.stack([series.reindex(t - pd.Timedelta(days=k)).to_numpy() for k in days]), axis=0)


def cv_score(df, features, transform, metric_fn, n_splits=4):
    days = (df["target_time"] - pd.Timedelta(hours=1)).dt.normalize()
    uniq = np.array(sorted(days.unique()))
    scores = []
    for tr_idx, va_idx in TimeSeriesSplit(n_splits=n_splits).split(uniq):
        tr, va = df[days.isin(uniq[tr_idx])], df[days.isin(uniq[va_idx])]
        m = fit_lgbm(tr[features], make_target(tr, transform, PLANT.capacity_kw), alpha=None)
        b = ForecastBundle(df.attrs["target"], features, {q: m for q in QUANTILES}, transform, PLANT.capacity_kw)
        scores.append(metric_fn(va, b.predict(va)["p50"].to_numpy()))
    return float(np.mean(scores))


def fit_bundle(target, train, features, transform, extra_meta) -> ForecastBundle:
    y = make_target(train, transform, PLANT.capacity_kw)
    models = {q: fit_lgbm(train[features], y, alpha=(None if q == "p50" else a)) for q, a in QUANTILES.items()}
    return ForecastBundle(target, features, models, transform, PLANT.capacity_kw if target == "solar" else None, extra_meta)


def log_run(name, params, metrics, bundle: ForecastBundle | None = None, register_as: str | None = None):
    with mlflow.start_run(run_name=name) as run:
        mlflow.log_params(params)
        mlflow.log_metrics({k: v for k, v in metrics.items() if v == v})  # NaN 제외
        version = None
        if bundle is not None:
            info = mlflow.pyfunc.log_model(name="model", python_model=PyfuncWrapper(bundle),
                                           registered_model_name=register_as,
                                           pip_requirements=["lightgbm", "pandas", "scikit-learn", "mlflow"],
                                           code_paths=[str(Path(__file__).parent)])
            version = info.registered_model_version
        return run.info.run_id, version


def assign_alias(model_name: str, version) -> str:
    """champion이 없으면 champion, 있으면 challenger (교체는 promote.py 규칙으로)."""
    client = MlflowClient()
    try:
        client.get_model_version_by_alias(model_name, "champion")
        alias = "challenger"
    except Exception:
        alias = "champion"
    client.set_registered_model_alias(model_name, alias, version)
    return alias


def run_solar():
    mlflow.set_experiment("solar")
    obs_df, clean_report = solar_training_frame("observation")
    fc_df, _ = solar_training_frame("forecast")
    for d in (obs_df, fc_df):
        d.attrs["target"] = "solar"
    train, _, test_start = time_split(obs_df)
    fc_train = fc_df[fc_df["target_time"] <= test_start]
    test = fc_df[fc_df["target_time"] > test_start]          # 운영과 같은 예보 입력
    test_obs = obs_df[obs_df["target_time"] > test_start]    # 관측 입력 (성능 상한 비교)
    cap = PLANT.capacity_kw
    nmae = lambda d, p: solar_metrics(d["generation_kwh"], p, cap)["nmae"]
    common = {"train_end": str(test_start.date()), "replay_days": REPLAY_DAYS, **clean_report}
    results = {}

    # 0. 베이스라인
    hist = obs_df.set_index("target_time")["generation_kwh"]
    base = same_hour_mean(hist, test["target_time"])
    m = solar_metrics(test["generation_kwh"], np.nan_to_num(base), cap, test["sky"])
    results["baseline"] = m
    log_run("baseline_7day_same_hour", {"model": "baseline", **common}, m)

    # 1. Ridge
    ridge = fit_ridge(train[SOLAR_FEATURES], train["generation_kwh"])
    b = ForecastBundle("solar", SOLAR_FEATURES, {q: ridge for q in QUANTILES}, "none", cap)
    m = solar_metrics(test["generation_kwh"], b.predict(test)["p50"], cap, test["sky"])
    results["ridge"] = m
    log_run("ridge", {"model": "ridge", **common}, m)

    # 2. LightGBM 후보: 원값 vs 청천 대비 비율, 관측 학습 vs 예보 학습
    candidates = {
        "lgbm_raw_obs": (train, "none"),
        "lgbm_csi_obs": (train, "csi"),
        "lgbm_csi_fcst": (fc_train, "csi"),
    }
    best_name, best_cv = None, float("inf")
    fitted = {}
    for name, (tr, transform) in candidates.items():
        tr = tr.copy()
        tr.attrs["target"] = "solar"
        cv = cv_score(tr, SOLAR_FEATURES, transform, nmae)
        bundle = fit_bundle("solar", tr, SOLAR_FEATURES, transform, {"experiment": name, "cv_nmae": cv})
        pred = bundle.predict(test)
        m = solar_metrics(test["generation_kwh"], pred["p50"], cap, test["sky"])
        m.update(cv_nmae=cv, coverage_p10_p90=coverage(test["generation_kwh"], pred["p10"], pred["p90"]))
        m["nmae_obs_inputs"] = solar_metrics(test_obs["generation_kwh"], bundle.predict(test_obs)["p50"], cap)["nmae"]
        results[name] = m
        fitted[name] = (bundle, m, transform, tr)
        if cv < best_cv:
            best_name, best_cv = name, cv

    # 3. 성능 상한: 관측 일사(icsr) 포함 - 운영 불가
    upper = fit_bundle("solar", train, SOLAR_FEATURES_UPPER, "csi", {})
    m = solar_metrics(test_obs["generation_kwh"], upper.predict(test_obs)["p50"], cap, test_obs["sky"])
    results["upper_bound_with_icsr"] = m
    log_run("upper_bound_with_icsr", {"model": "lgbm", "transform": "csi", "features": "+icsr", **common}, m)

    for name, (bundle, m, transform, tr) in fitted.items():
        is_best = name == best_name
        _, version = log_run(name, {"model": "lgbm_quantile", "transform": transform,
                                    "features": ",".join(SOLAR_FEATURES), "train_rows": len(tr), **common},
                             m, bundle if is_best else None, MODEL_NAMES["solar"] if is_best else None)
        if is_best:
            alias = assign_alias(MODEL_NAMES["solar"], version)
            save_local("solar", bundle, version, alias, m)
    write_report("solar", results, best_name)
    return results, best_name


def run_demand():
    mlflow.set_experiment("demand")
    obs_df = demand_training_frame("observation")
    fc_df = demand_training_frame("forecast")
    train, _, test_start = time_split(obs_df)
    fc_train = fc_df[fc_df["target_time"] <= test_start]
    test = fc_df[fc_df["target_time"] > test_start]
    common = {"train_end": str(test_start.date()), "replay_days": REPLAY_DAYS}
    mape = lambda d, p: demand_metrics(d["demand_mwh"], p, d["target_time"])["mape"]
    results = {}

    m = demand_metrics(test["demand_mwh"], test["lag_7d"], test["target_time"])
    results["baseline"] = m
    log_run("baseline_last_week", {"model": "baseline", **common}, m)

    ridge = fit_ridge(train[DEMAND_FEATURES], train["demand_mwh"])
    m = demand_metrics(test["demand_mwh"], ridge.predict(test[DEMAND_FEATURES]), test["target_time"])
    results["ridge"] = m
    log_run("ridge", {"model": "ridge", **common}, m)

    best_name, best_cv, fitted = None, float("inf"), {}
    for name, tr in {"lgbm_obs": train, "lgbm_fcst": fc_train}.items():
        tr = tr.copy()
        tr.attrs["target"] = "demand"
        cv = cv_score(tr, DEMAND_FEATURES, "none", mape)
        bundle = fit_bundle("demand", tr, DEMAND_FEATURES, "none", {"experiment": name, "cv_mape": cv})
        pred = bundle.predict(test)
        m = demand_metrics(test["demand_mwh"], pred["p50"], test["target_time"])
        m.update(cv_mape=cv, coverage_p10_p90=coverage(test["demand_mwh"], pred["p10"], pred["p90"]))
        results[name] = m
        fitted[name] = (bundle, m, tr)
        if cv < best_cv:
            best_name, best_cv = name, cv

    for name, (bundle, m, tr) in fitted.items():
        is_best = name == best_name
        _, version = log_run(name, {"model": "lgbm_quantile", "features": ",".join(DEMAND_FEATURES),
                                    "train_rows": len(tr), **common},
                             m, bundle if is_best else None, MODEL_NAMES["demand"] if is_best else None)
        if is_best:
            alias = assign_alias(MODEL_NAMES["demand"], version)
            save_local("demand", bundle, version, alias, m)
    write_report("demand", results, best_name)
    return results, best_name


def _has_champion(model_name) -> bool:
    try:
        MlflowClient().get_model_version_by_alias(model_name, "champion")
        return True
    except Exception:
        return False


def save_local(target, bundle, version, alias, metrics):
    """MLflow에 못 붙을 때 모델 서버가 쓰는 대체 파일."""
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    bundle.meta.update(version=str(version), alias=alias, metrics=metrics)
    joblib.dump(bundle, ARTIFACT_DIR / f"{target}.joblib")


def write_report(target, results, best):
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    (ARTIFACT_DIR / f"report_{target}.json").write_text(
        json.dumps({"best": best, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=["solar", "demand", "all"], default="all")
    ap.add_argument("--if-missing", action="store_true", help="champion이 이미 있으면 건너뛴다 (컨테이너 첫 실행용)")
    args = ap.parse_args()
    print("mlflow:", setup_mlflow())
    targets = ["solar", "demand"] if args.target == "all" else [args.target]
    if args.if_missing:
        targets = [t for t in targets if not _has_champion(MODEL_NAMES[t])]
        if not targets:
            print("champion 있음, 학습 건너뜀")
    for t in targets:
        results, best = run_solar() if t == "solar" else run_demand()
        print(f"\n[{t}] 선택: {best}")
        print(pd.DataFrame(results).T.round(2).to_string())


if __name__ == "__main__":
    main()
