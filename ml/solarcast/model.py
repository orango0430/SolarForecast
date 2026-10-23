"""모델 번들, 평가 지표, MLflow pyfunc 래퍼."""
from dataclasses import dataclass, field

import lightgbm as lgb
import mlflow.pyfunc
import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

QUANTILES = {"p10": 0.1, "p50": 0.5, "p90": 0.9}


@dataclass
class ForecastBundle:
    """target: solar|demand. transform='csi'이면 청천일사 대비 비율로 학습 후 되돌린다."""
    target: str
    features: list[str]
    models: dict
    transform: str = "none"
    capacity_kw: float | None = None
    meta: dict = field(default_factory=dict)

    def _scale(self, X: pd.DataFrame) -> np.ndarray:
        return (self.capacity_kw * X["clearsky_ghi"].to_numpy() / 1000.0).clip(min=0)

    def predict(self, X: pd.DataFrame) -> pd.DataFrame:
        out = {}
        for q, m in self.models.items():
            y = m.predict(X[self.features])
            if self.transform == "csi":
                y = y * self._scale(X)
            out[q] = y
        vals = pd.DataFrame(out)[list(QUANTILES)].to_numpy()
        if self.target == "solar":
            vals = vals.clip(0, self.capacity_kw)
            vals[X["clearsky_ghi"].to_numpy() <= 0] = 0.0
        # 분위수 교차 방지
        return pd.DataFrame(np.sort(vals, axis=1), columns=list(QUANTILES))


class PyfuncWrapper(mlflow.pyfunc.PythonModel):
    def __init__(self, bundle: ForecastBundle):
        self.bundle = bundle

    def predict(self, context, model_input: pd.DataFrame, params=None):
        return self.bundle.predict(model_input)


def make_target(df: pd.DataFrame, bundle_transform: str, capacity_kw: float | None):
    if bundle_transform == "csi":
        scale = capacity_kw * df["clearsky_ghi"].to_numpy() / 1000.0
        y = np.where(scale > 1e-6, df["generation_kwh"].to_numpy() / np.maximum(scale, 1e-6), 0.0)
        return np.clip(y, 0, 1.5)
    col = "generation_kwh" if "generation_kwh" in df else "demand_mwh"
    return df[col].to_numpy()


def fit_lgbm(X, y, alpha: float | None, params: dict | None = None):
    p = dict(n_estimators=600, learning_rate=0.03, num_leaves=31, min_child_samples=30,
             subsample=0.8, subsample_freq=1, colsample_bytree=0.8, verbose=-1)
    p.update(params or {})
    if alpha is None:
        m = lgb.LGBMRegressor(objective="l1", **p)
    else:
        m = lgb.LGBMRegressor(objective="quantile", alpha=alpha, **p)
    return m.fit(X, y)


def fit_ridge(X, y):
    return make_pipeline(StandardScaler(), Ridge(alpha=1.0)).fit(X, y)


# ---- 지표 ----

def solar_metrics(y_true, y_pred, capacity_kw, sky=None) -> dict:
    y_true, y_pred = np.asarray(y_true), np.asarray(y_pred)
    err = np.abs(y_pred - y_true) / capacity_kw * 100
    util = y_true / capacity_kw
    m = {
        # 주 지표: 이용률 10% 이상 시간 (예측제도 정산 기준과 같은 방식)
        "nmae": float(err[util >= 0.1].mean()),
        "nmae_all_hours": float(err.mean()),
        "nmae_low_util": float(err[util < 0.1].mean()) if (util < 0.1).any() else float("nan"),
    }
    if sky is not None:
        sky = np.asarray(sky)
        for code, name in [(1, "clear"), (3, "partly"), (4, "overcast")]:
            mask = (sky == code) & (util >= 0.1)
            m[f"nmae_{name}"] = float(err[mask].mean()) if mask.any() else float("nan")
    return m


def demand_metrics(y_true, y_pred, times) -> dict:
    y_true, y_pred = np.asarray(y_true), np.asarray(y_pred)
    df = pd.DataFrame({"t": pd.to_datetime(times), "y": y_true, "p": y_pred})
    df["date"] = (df["t"] - pd.Timedelta(hours=1)).dt.normalize()
    peak = df.loc[df.groupby("date")["y"].idxmax()]
    return {
        "mape": float((np.abs(df["p"] - df["y"]) / df["y"]).mean() * 100),
        "peak_mape": float((np.abs(peak["p"] - peak["y"]) / peak["y"]).mean() * 100),
    }


def coverage(y, p10, p90) -> float:
    y = np.asarray(y)
    return float(((y >= p10) & (y <= p90)).mean() * 100)
