"""정제와 학습 데이터셋 구성 (기획서 3.5 정제 규칙)."""
import numpy as np
import pandas as pd

from .config import DEMAND_STATIONS, PLANT, PROCESSED_DIR
from .features import demand_features, obs_to_forecast_like, solar_features
from .grid import latlon_to_grid


def load(name: str) -> pd.DataFrame:
    time_cols = {"weather_observation": ["obs_time"], "weather_forecast": ["base_time", "fcst_time"],
                 "solar_actual": ["target_time"], "demand_actual": ["target_time"]}
    return pd.read_csv(PROCESSED_DIR / f"{name}.csv", parse_dates=time_cols.get(name, []))


def clean_solar(solar: pd.DataFrame, geo_night: pd.Series, obs_icsr: pd.Series) -> pd.DataFrame:
    """solar: target_time, generation_kwh (같은 순서의 야간 여부, 관측 일사 시리즈).

    - 야간은 0 고정
    - 결측 2시간 이하는 보간, 그보다 길면 해당 일 제외
    - 일사가 충분한데 발전이 거의 없는 날은 설비 정지로 보고 제외
    """
    s = solar.copy().reset_index(drop=True)
    s.loc[geo_night.to_numpy(), "generation_kwh"] = 0.0
    s["date"] = (s["target_time"] - pd.Timedelta(hours=1)).dt.normalize()

    isna = s["generation_kwh"].isna()
    run_id = (isna != isna.shift()).cumsum()
    run_len = isna.groupby(run_id).transform("sum")
    long_gap_days = set(s.loc[isna & (run_len > 2), "date"])
    s["generation_kwh"] = s["generation_kwh"].interpolate(limit=2)

    s["icsr"] = obs_icsr.to_numpy()
    daily = s.groupby("date").agg(gen=("generation_kwh", "sum"), icsr=("icsr", "sum"))
    expected = daily["icsr"] / 3.6 * PLANT.capacity_kw * 0.8  # MJ/m2 -> kWh/m2 x 용량 x 성능비
    ratio = daily["gen"] / expected.replace(0, np.nan)
    outage_days = set(daily.index[(daily["icsr"] > 3) & (ratio < 0.2)])

    drop = long_gap_days | outage_days
    s = s[~s["date"].isin(drop)].drop(columns=["date", "icsr"])
    s["utilization"] = s["generation_kwh"] / PLANT.capacity_kw
    return s.dropna(subset=["generation_kwh"]), {"long_gap_days": len(long_gap_days), "outage_days": len(outage_days)}


def solar_training_frame(source: str = "observation") -> tuple[pd.DataFrame, dict]:
    """source='observation' : 관측을 예보 체계로 변환한 피처 (+icsr, 상한 실험용)
       source='forecast'    : 과거 단기예보 아카이브로 직접 만든 피처
    """
    solar = load("solar_actual")
    obs = load("weather_observation")
    obs = obs[obs["stn_id"] == PLANT.asos_stn_id].sort_values("obs_time")

    if source == "observation":
        weather = obs_to_forecast_like(obs)
    else:
        nx, ny = latlon_to_grid(PLANT.lat, PLANT.lon)
        f = load("weather_forecast")
        f = f[(f["nx"] == nx) & (f["ny"] == ny)]
        weather = f.rename(columns={"fcst_time": "target_time"})[["target_time", "tmp", "reh", "sky", "pty", "wsd"]]
        weather = weather.merge(obs[["obs_time", "icsr"]].rename(columns={"obs_time": "target_time"}), on="target_time")
    weather = weather.drop(columns=[c for c in ["pcp"] if c in weather])

    X = solar_features(weather, PLANT.lat, PLANT.lon)
    df = X.merge(solar[["target_time", "generation_kwh"]], on="target_time", how="inner")
    night = df["clearsky_ghi"] <= 0
    cleaned, report = clean_solar(df[["target_time", "generation_kwh"]], night, df["icsr"])
    df = df.drop(columns=["generation_kwh"]).merge(cleaned, on="target_time")
    return df.sort_values("target_time").reset_index(drop=True), report


def demand_weather_from_obs(obs: pd.DataFrame) -> pd.DataFrame:
    o = obs[obs["stn_id"].isin(DEMAND_STATIONS)]
    return o.rename(columns={"obs_time": "target_time", "ta": "tmp", "hm": "reh"})[["target_time", "tmp", "reh"]]


def demand_weather_from_forecast(fcst: pd.DataFrame) -> pd.DataFrame:
    grids = {latlon_to_grid(lat, lon) for _, lat, lon in DEMAND_STATIONS.values()}
    mask = [(x, y) in grids for x, y in zip(fcst["nx"], fcst["ny"])]
    return fcst[mask].rename(columns={"fcst_time": "target_time"})[["target_time", "tmp", "reh"]]


def demand_training_frame(source: str = "observation") -> pd.DataFrame:
    demand = load("demand_actual").sort_values("target_time")
    history = demand.set_index("target_time")["demand_mwh"]
    if source == "observation":
        weather = demand_weather_from_obs(load("weather_observation"))
    else:
        weather = demand_weather_from_forecast(load("weather_forecast"))
    # 학습에서는 모든 라그가 2일 이상 과거라 issued_at 필터가 결과를 바꾸지 않는다 (tests/test_leakage.py)
    X = demand_features(weather, history, issued_at=history.index.max())
    df = X.merge(demand, on="target_time").dropna(subset=["lag_7d", "week_mean", "demand_mwh"])
    return df.sort_values("target_time").reset_index(drop=True)
