"""피처 생성. 학습과 서빙이 같은 함수를 쓴다 (학습/서빙 불일치 방지).

시간 규칙: 모든 시각은 KST naive, hour-ending (target_time 13:00 = 12:00~13:00 구간).
"""
import holidays
import numpy as np
import pandas as pd
import pvlib

from .config import CDD_BASE, HDD_BASE, TZ

SOLAR_FEATURES = [
    "hour", "doy_sin", "doy_cos",
    "tmp", "reh", "sky", "pty", "wsd",
    "elevation", "azimuth", "clearsky_ghi",
]
# 성능 상한 실험용: 관측 일사를 넣은 모델 (운영에서는 쓸 수 없음)
SOLAR_FEATURES_UPPER = SOLAR_FEATURES + ["icsr"]

DEMAND_FEATURES = [
    "hour", "dow", "month", "is_weekend", "is_holiday", "near_holiday",
    "tmp", "reh", "cdd", "hdd",
    "lag_7d", "week_mean",
]

_KR_HOLIDAYS = holidays.KR(years=range(2015, 2031))


def sky_from_cloud(dc10: pd.Series) -> pd.Series:
    """관측 전운량(0~10)을 단기예보 SKY 코드로: 맑음1(0~5), 구름많음3(6~8), 흐림4(9~10)."""
    return pd.cut(dc10.fillna(0), bins=[-1, 5, 8, 10], labels=[1, 3, 4]).astype(int)


def obs_to_forecast_like(obs: pd.DataFrame) -> pd.DataFrame:
    """ASOS 관측을 단기예보 변수 체계로 변환. 학습 피처를 예보 가능한 변수로 제한하기 위함."""
    rn = obs["rn"].fillna(0)
    pty = np.where(rn > 0, np.where(obs["ta"] < 0, 3, 1), 0)
    out = pd.DataFrame({
        "target_time": obs["obs_time"],
        "tmp": obs["ta"],
        "reh": obs["hm"],
        "sky": sky_from_cloud(obs["dc10_tca"]),
        "pty": pty,
        "pcp": rn,
        "wsd": obs["ws"],
    })
    if "icsr" in obs:
        out["icsr"] = obs["icsr"]
    return out.reset_index(drop=True)


def solar_geometry(times: pd.Series, lat: float, lon: float) -> pd.DataFrame:
    """구간 중앙 시각(-30분) 기준 태양 고도·방위각과 청천일사량(W/m2). 날씨와 무관한 계산값."""
    mid = pd.DatetimeIndex(pd.to_datetime(times)) - pd.Timedelta(minutes=30)
    mid = mid.tz_localize(TZ)
    loc = pvlib.location.Location(lat, lon, tz=TZ)
    pos = loc.get_solarposition(mid)
    cs = loc.get_clearsky(mid, model="ineichen")
    return pd.DataFrame({
        "elevation": pos["apparent_elevation"].clip(lower=-10).to_numpy(),
        "azimuth": pos["azimuth"].to_numpy(),
        "clearsky_ghi": cs["ghi"].clip(lower=0).to_numpy(),
    })


def _calendar(times: pd.Series) -> pd.DataFrame:
    t = pd.DatetimeIndex(pd.to_datetime(times))
    # hour-ending 24시는 다음날 00:00으로 표현되므로 날짜는 구간 시작 기준으로 본다
    start = t - pd.Timedelta(hours=1)
    hour = start.hour + 1
    doy = start.day_of_year
    return pd.DataFrame({
        "hour": hour,
        "doy_sin": np.sin(2 * np.pi * doy / 365.25),
        "doy_cos": np.cos(2 * np.pi * doy / 365.25),
        "dow": start.day_of_week,
        "month": start.month,
        "date": start.normalize(),
    })


def solar_features(weather: pd.DataFrame, lat: float, lon: float) -> pd.DataFrame:
    """weather: target_time, tmp, reh, sky, pty, wsd (+icsr 선택)."""
    w = weather.reset_index(drop=True)
    cal = _calendar(w["target_time"])
    geo = solar_geometry(w["target_time"], lat, lon)
    X = pd.concat([cal[["hour", "doy_sin", "doy_cos"]], w.drop(columns=["target_time"]), geo], axis=1)
    X.insert(0, "target_time", pd.to_datetime(w["target_time"]).to_numpy())
    return X


def holiday_flags(dates: pd.Series) -> pd.DataFrame:
    d = pd.to_datetime(dates)
    is_hol = d.map(lambda x: x.date() in _KR_HOLIDAYS).astype(int)
    prev_hol = (d - pd.Timedelta(days=1)).map(lambda x: x.date() in _KR_HOLIDAYS)
    next_hol = (d + pd.Timedelta(days=1)).map(lambda x: x.date() in _KR_HOLIDAYS)
    near = ((prev_hol | next_hol) & (is_hol == 0)).astype(int)
    return pd.DataFrame({"is_holiday": is_hol.to_numpy(), "near_holiday": near.to_numpy()})


def demand_lag_features(target_times: pd.Series, history: pd.Series, issued_at: pd.Timestamp) -> pd.DataFrame:
    """7일 전 같은 시각 수요, 2~8일 전 같은 시각 평균.

    issued_at 이후의 실측은 버린다(누수 방지). D+1 예측 시 D일 18시 이후 값은 알 수 없으므로
    '최근 1주 평균'도 D-1 ~ D-7 (2~8일 전)만 쓴다.
    """
    hist = history[history.index <= issued_at]
    t = pd.DatetimeIndex(pd.to_datetime(target_times))
    lag7 = hist.reindex(t - pd.Timedelta(days=7)).to_numpy()
    week = np.nanmean(np.stack([hist.reindex(t - pd.Timedelta(days=k)).to_numpy() for k in range(2, 9)]), axis=0)
    return pd.DataFrame({"lag_7d": lag7, "week_mean": week})


def demand_features(weather: pd.DataFrame, history: pd.Series, issued_at: pd.Timestamp) -> pd.DataFrame:
    """weather: target_time, tmp, reh (도시별 행이 여러 개면 시각별 평균)."""
    w = (weather.groupby("target_time", as_index=False)[["tmp", "reh"]].mean()
         .sort_values("target_time").reset_index(drop=True))
    cal = _calendar(w["target_time"])
    hol = holiday_flags(cal["date"])
    X = pd.DataFrame({
        "target_time": pd.to_datetime(w["target_time"]).to_numpy(),
        "hour": cal["hour"],
        "dow": cal["dow"],
        "month": cal["month"],
        "is_weekend": (cal["dow"] >= 5).astype(int),
        "is_holiday": hol["is_holiday"],
        "near_holiday": hol["near_holiday"],
        "tmp": w["tmp"],
        "reh": w["reh"],
        "cdd": (w["tmp"] - CDD_BASE).clip(lower=0),
        "hdd": (HDD_BASE - w["tmp"]).clip(lower=0),
    })
    lags = demand_lag_features(X["target_time"], history, issued_at)
    return pd.concat([X, lags], axis=1)


def issued_at_for(target_date: pd.Timestamp) -> pd.Timestamp:
    """D+1 예측의 발표 기준 시각 = D일 17:00."""
    return pd.Timestamp(target_date).normalize() - pd.Timedelta(days=1) + pd.Timedelta(hours=17)


def day_target_times(target_date: pd.Timestamp) -> pd.DatetimeIndex:
    """D+1일 1~24시 (hour-ending)."""
    d = pd.Timestamp(target_date).normalize()
    return pd.date_range(d + pd.Timedelta(hours=1), d + pd.Timedelta(hours=24), freq="h")
