"""학습 곡선 기록: 부스팅 반복 수에 따른 학습/검증 오차를 MLflow에 step 지표로 남긴다.

서빙 모델과 같은 설정(champion으로 뽑힌 조합)으로 P50 모델을 다시 학습하되, 모델 등록은 하지 않는다.
검증 = 학습에 쓰지 않은 리플레이 구간, 입력은 운영과 같은 예보 기상.

    python -m solarcast.curves            # solar, demand 모두
    python -m solarcast.curves --target solar
"""
import argparse

import mlflow
import numpy as np

from .config import PLANT
from .dataset import demand_training_frame, solar_training_frame
from .features import DEMAND_FEATURES, SOLAR_FEATURES
from .model import QUANTILES, ForecastBundle, demand_metrics, fit_lgbm, make_target, solar_metrics
from .train import setup_mlflow, time_split

STEP = 20


def _curve(target, train, valid, features, transform, score):
    model = fit_lgbm(train[features], make_target(train, transform, PLANT.capacity_kw), alpha=None)
    n = model.n_estimators
    cap = PLANT.capacity_kw if target == "solar" else None
    rows = []
    for k in list(range(STEP, n, STEP)) + [n]:
        class _AtK:  # k번째 반복까지만 쓰는 예측기
            def predict(self, X, _k=k):
                return model.predict(X, num_iteration=_k)
        b = ForecastBundle(target, features, {q: _AtK() for q in QUANTILES}, transform, cap)
        rows.append((k, score(train, b.predict(train)["p50"]), score(valid, b.predict(valid)["p50"])))
    return rows


def run(target):
    if target == "solar":
        obs, _ = solar_training_frame("observation")
        fc, _ = solar_training_frame("forecast")
        train, _, test_start = time_split(obs)
        valid = fc[fc["target_time"] > test_start]
        features, transform, key = SOLAR_FEATURES, "csi", "nmae"
        score = lambda d, p: solar_metrics(d["generation_kwh"], p, PLANT.capacity_kw)["nmae"]
    else:
        obs = demand_training_frame("observation")
        fc = demand_training_frame("forecast")
        train, _, test_start = time_split(obs)
        valid = fc[fc["target_time"] > test_start]
        features, transform, key = DEMAND_FEATURES, "none", "mape"
        score = lambda d, p: demand_metrics(d["demand_mwh"], p, d["target_time"])["mape"]

    mlflow.set_experiment(f"{target}-learning-curve")
    with mlflow.start_run(run_name=f"lgbm_p50_{transform}"):
        mlflow.log_params({"model": "lgbm_p50", "transform": transform, "train_end": str(test_start.date()),
                           "valid": "replay period, forecast inputs", "step": STEP})
        rows = _curve(target, train, valid, features, transform, score)
        for k, tr, va in rows:
            mlflow.log_metrics({f"train_{key}": tr, f"valid_{key}": va}, step=k)
        best_k, _, best = min(rows, key=lambda r: r[2])
        mlflow.log_metrics({f"best_valid_{key}": best, "best_iteration": best_k})
    print(f"[{target}] 반복 {rows[-1][0]}: train {rows[-1][1]:.2f}% / valid {rows[-1][2]:.2f}% "
          f"(최저 valid {best:.2f}% @ {best_k})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=["solar", "demand", "all"], default="all")
    a = ap.parse_args()
    setup_mlflow()
    for t in (["solar", "demand"] if a.target == "all" else [a.target]):
        run(t)


if __name__ == "__main__":
    main()
