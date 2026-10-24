"""등록된 모델을 임의 기간으로 평가 (운영과 같은 예보 입력). 월별 지표와 베이스라인을 함께 낸다.

    python -m solarcast.evaluate --target demand --alias champion --start 2026-01-01
"""
import argparse
import json

import mlflow
import mlflow.pyfunc
import numpy as np
import pandas as pd

from .config import PLANT
from .dataset import demand_training_frame, solar_training_frame
from .model import demand_metrics, solar_metrics
from .train import ARTIFACT_DIR, MODEL_NAMES, same_hour_mean, setup_mlflow


def frame(target):
    return solar_training_frame("forecast")[0] if target == "solar" else demand_training_frame("forecast")


def score(target, df, pred):
    if target == "solar":
        return solar_metrics(df["generation_kwh"], pred, PLANT.capacity_kw, df["sky"])
    return demand_metrics(df["demand_mwh"], pred, df["target_time"])


def baseline(target, full, df):
    if target == "solar":
        hist = full.set_index("target_time")["generation_kwh"]
        return np.nan_to_num(same_hour_mean(hist, df["target_time"]))
    return df["lag_7d"].to_numpy()


def evaluate(target, alias, start, end=None, full=None):
    full = frame(target) if full is None else full
    df = full[full["target_time"] > pd.Timestamp(start)]
    if end:
        df = df[df["target_time"] <= pd.Timestamp(end) + pd.Timedelta(hours=24)]
    name = MODEL_NAMES[target]
    mv = mlflow.MlflowClient().get_model_version_by_alias(name, alias)
    bundle = mlflow.pyfunc.load_model(f"models:/{name}@{alias}").unwrap_python_model().bundle
    pred = bundle.predict(df)["p50"].to_numpy()
    base = baseline(target, full, df)
    key = "nmae" if target == "solar" else "mape"
    month = (df["target_time"] - pd.Timedelta(hours=1)).dt.strftime("%Y-%m").to_numpy()
    monthly = {}
    for m in sorted(set(month)):
        mask = month == m
        monthly[m] = {"model": round(score(target, df[mask], pred[mask])[key], 2),
                      "baseline": round(score(target, df[mask], base[mask])[key], 2)}
    return {"target": target, "alias": alias, "version": str(mv.version),
            "period": f"{df['target_time'].min()} ~ {df['target_time'].max()}", "hours": int(len(df)),
            "model": {k: round(v, 2) for k, v in score(target, df, pred).items()},
            "baseline": {k: round(v, 2) for k, v in score(target, df, base).items()},
            "monthly": monthly}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=list(MODEL_NAMES), required=True)
    ap.add_argument("--alias", default="champion")
    ap.add_argument("--start", required=True)
    ap.add_argument("--end")
    ap.add_argument("--save", help="ml/artifacts/에 저장할 파일 이름")
    a = ap.parse_args()
    setup_mlflow()
    r = evaluate(a.target, a.alias, a.start, a.end)
    print(json.dumps(r, ensure_ascii=False, indent=2))
    if a.save:
        (ARTIFACT_DIR / a.save).write_text(json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
