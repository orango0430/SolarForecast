"""모델 교체 규칙 (기획서 7.1).

challenger가 최근 30일 리플레이 구간에서 champion보다 주 지표가 낮고,
태양광은 하늘상태별(맑음·구름많음·흐림) 오차도 나빠지지 않을 때만 champion으로 승격한다.
롤백은 alias를 이전 버전으로 되돌리는 것으로 끝난다.

    python -m solarcast.promote solar          # 비교만
    python -m solarcast.promote solar --apply  # 규칙 통과 시 승격
"""
import argparse
import json

import mlflow.pyfunc
import pandas as pd
from mlflow import MlflowClient

from .config import PLANT
from .dataset import demand_training_frame, solar_training_frame
from .model import demand_metrics, solar_metrics
from .train import MODEL_NAMES, setup_mlflow

SKY_TOLERANCE = 0.1  # 하늘상태별 NMAE 허용 악화폭 (%p)


def _eval_frame(target, days):
    df = solar_training_frame("forecast")[0] if target == "solar" else demand_training_frame("forecast")
    start = df["target_time"].max().normalize() - pd.Timedelta(days=days - 1)
    return df[df["target_time"] > start]


def _score(target, alias, df):
    bundle = mlflow.pyfunc.load_model(f"models:/{MODEL_NAMES[target]}@{alias}").unwrap_python_model().bundle
    p = bundle.predict(df)["p50"]
    if target == "solar":
        return solar_metrics(df["generation_kwh"], p, PLANT.capacity_kw, df["sky"])
    return demand_metrics(df["demand_mwh"], p, df["target_time"])


def compare(target: str, days: int = 30) -> dict:
    df = _eval_frame(target, days)
    champ, chall = _score(target, "champion", df), _score(target, "challenger", df)
    key = "nmae" if target == "solar" else "mape"
    reasons = []
    if chall[key] >= champ[key]:
        reasons.append(f"{key} 개선 없음 ({chall[key]:.2f} >= {champ[key]:.2f})")
    if target == "solar":
        for sky in ("clear", "partly", "overcast"):
            k = f"nmae_{sky}"
            if chall[k] == chall[k] and champ[k] == champ[k] and chall[k] > champ[k] + SKY_TOLERANCE:
                reasons.append(f"{k} 악화 ({chall[k]:.2f} > {champ[k]:.2f})")
    return {"target": target, "days": days, "champion": champ, "challenger": chall,
            "passed": not reasons, "reasons": reasons}


def promote(target: str, days: int = 30) -> dict:
    client = MlflowClient()
    name = MODEL_NAMES[target]
    result = compare(target, days)
    if result["passed"]:
        old = client.get_model_version_by_alias(name, "champion").version
        new = client.get_model_version_by_alias(name, "challenger").version
        client.set_registered_model_alias(name, "champion", new)
        client.set_registered_model_alias(name, "previous", old)
        client.delete_registered_model_alias(name, "challenger")
        result.update(promoted=new, previous=old)
    return result


def rollback(target: str, version: str | None = None) -> dict:
    client = MlflowClient()
    name = MODEL_NAMES[target]
    if version is None:
        version = client.get_model_version_by_alias(name, "previous").version
    current = client.get_model_version_by_alias(name, "champion").version
    client.set_registered_model_alias(name, "champion", version)
    client.set_registered_model_alias(name, "previous", current)
    return {"target": target, "champion": version, "previous": current}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target", choices=list(MODEL_NAMES))
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    setup_mlflow()
    r = promote(a.target, a.days) if a.apply else compare(a.target, a.days)
    print(json.dumps(r, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
