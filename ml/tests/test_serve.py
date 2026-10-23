import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from solarcast import serve
from solarcast.features import DEMAND_FEATURES, SOLAR_FEATURES, day_target_times
from solarcast.model import QUANTILES, ForecastBundle


class _Const:
    def __init__(self, v):
        self.v = v

    def predict(self, X):
        return np.full(len(X), self.v)


@pytest.fixture
def client(monkeypatch):
    reg = serve.Registry()
    reg.client = None
    reg.models = {
        "solar": serve.LoadedModel(ForecastBundle("solar", SOLAR_FEATURES, {q: _Const(0.6) for q in QUANTILES},
                                                  "csi", 1000.0), "1", "test"),
        "demand": serve.LoadedModel(ForecastBundle("demand", DEMAND_FEATURES, {q: _Const(60000) for q in QUANTILES}),
                                    "1", "test"),
    }
    monkeypatch.setattr(reg, "refresh", lambda: None)  # 실제 모델 파일을 읽지 않게
    monkeypatch.setattr(serve, "registry", reg)
    with TestClient(serve.app) as c:
        yield c


def _weather(day):
    return [{"fcst_time": t.isoformat(), "tmp": 20, "reh": 60, "sky": 1, "pty": 0, "wsd": 2}
            for t in day_target_times(pd.Timestamp(day))]


def test_solar_predict_zero_at_night(client):
    r = client.post("/predict", json={"target": "solar", "target_date": "2025-06-21", "weather": _weather("2025-06-21")})
    assert r.status_code == 200, r.text
    preds = r.json()["predictions"]
    assert len(preds) == 24
    assert preds[0]["p50"] == 0 and preds[12]["p50"] > 0
    assert all(p["p10"] <= p["p50"] <= p["p90"] for p in preds)


def test_demand_requires_history(client):
    r = client.post("/predict", json={"target": "demand", "target_date": "2025-06-21", "weather": _weather("2025-06-21")})
    assert r.status_code == 422


def test_demand_predict(client):
    hist = pd.date_range("2025-06-10 01:00", "2025-06-20 17:00", freq="h")
    body = {"target": "demand", "target_date": "2025-06-21", "weather": _weather("2025-06-21"),
            "history": [{"time": t.isoformat(), "value": 60000} for t in hist]}
    r = client.post("/predict", json=body)
    assert r.status_code == 200, r.text
    assert r.json()["predictions"][5]["p50"] == 60000


def test_solar_rejects_weather_for_other_day(client):
    bad = _weather("2025-06-22")  # 날짜가 맞지 않는 기상
    r = client.post("/predict", json={"target": "solar", "target_date": "2025-06-21", "weather": bad})
    assert r.status_code == 422
