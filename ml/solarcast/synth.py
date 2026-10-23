"""합성 데이터 생성기.

실데이터(공공데이터포털 CSV, 기상청 API 키)를 받기 전에 파이프라인 전체를 검증하기 위한 데이터.
물리적으로 그럴듯하게 만들었지만 여기서 나온 성능 수치는 성과로 쓰지 않는다.

- 날씨: 일 단위 맑음/구름많음/흐림 마르코프 체인 + 시간별 잡음
- 일사: pvlib 청천일사 x Kasten-Czeplak 구름 감쇠
- 발전: 설비용량 x 일사/1000 x 성능비 x 온도 손실, 가끔 설비 정지일과 결측
- 예보: 실제 날씨에 일 단위 편향 + 시간 잡음을 얹은 값 (관측-예보 불일치 재현)
"""
import argparse

import numpy as np
import pandas as pd

from .config import DEMAND_STATIONS, PLANT, PROCESSED_DIR
from .features import issued_at_for, sky_from_cloud, solar_geometry
from .grid import latlon_to_grid

# 계절별 (맑음, 구름많음, 흐림) 전이 확률의 정상분포 근사치
REGIME_CLOUD = np.array([2.0, 6.5, 9.3])


def _regimes(days: pd.DatetimeIndex, rng, wet_summer=True) -> np.ndarray:
    out = np.zeros(len(days), dtype=int)
    state = 0
    for i, d in enumerate(days):
        summer = d.month in (6, 7, 8) and wet_summer
        stay = 0.55
        p = np.array([0.45, 0.30, 0.25]) if not summer else np.array([0.25, 0.30, 0.45])
        probs = p * (1 - stay)
        probs[state] += stay
        state = rng.choice(3, p=probs / probs.sum())
        out[i] = state
    return out


def _station_weather(times, lat, lon, annual, amp, rng, regimes_daily, day_index):
    n = len(times)
    start = times - pd.Timedelta(hours=1)
    doy = start.day_of_year.to_numpy()
    hour = start.hour.to_numpy()

    base_cloud = REGIME_CLOUD[regimes_daily[day_index]]
    noise = np.zeros(n)
    for i in range(1, n):
        noise[i] = 0.85 * noise[i - 1] + rng.normal(0, 0.6)
    cloud = np.clip(base_cloud + noise, 0, 10)

    anomaly_day = np.zeros(regimes_daily.size)
    for i in range(1, anomaly_day.size):
        anomaly_day[i] = 0.8 * anomaly_day[i - 1] + rng.normal(0, 1.4)
    seasonal = annual - amp * np.cos(2 * np.pi * (doy - 20) / 365.25)
    diurnal = (4.5 - 0.3 * cloud) * np.sin(2 * np.pi * (hour - 9) / 24)
    ta = seasonal + anomaly_day[day_index] + diurnal + rng.normal(0, 0.4, n)

    rain = (cloud > 8.5) & (rng.random(n) < 0.35)
    rn = np.where(rain, rng.exponential(1.8, n), 0.0)
    hm = np.clip(62 + 2.8 * cloud - 1.2 * diurnal + 10 * rain + rng.normal(0, 5, n), 15, 100)
    ws = rng.gamma(2.2, 1.3, n) + 0.08 * cloud

    geo = solar_geometry(pd.Series(times), lat, lon)
    ghi = geo["clearsky_ghi"].to_numpy() * (1 - 0.75 * (cloud / 10) ** 3.4) * rng.normal(1, 0.06, n)
    ghi = np.clip(ghi, 0, None)
    return pd.DataFrame({
        "obs_time": times,
        "ta": ta.round(1),
        "hm": hm.round(0),
        "rn": rn.round(1),
        "ws": ws.round(1),
        "dc10_tca": cloud.round(0).astype(int),
        "ss": np.where(geo["elevation"] > 0, np.clip(1 - cloud / 10 + rng.normal(0, 0.05, n), 0, 1), 0).round(1),
        "icsr": (ghi * 0.0036).round(2),  # W/m2 1시간 평균 -> MJ/m2
    }), ghi, cloud


def _forecast_from_obs(obs: pd.DataFrame, cloud: np.ndarray, nx: int, ny: int, rng) -> pd.DataFrame:
    times = pd.DatetimeIndex(obs["obs_time"])
    start = times - pd.Timedelta(hours=1)
    day = start.normalize()
    day_codes, day_idx = np.unique(day, return_inverse=True)
    n = len(obs)

    day_bias = rng.normal(0, 2.0, len(day_codes))[day_idx]
    f_cloud = np.clip(cloud + day_bias + rng.normal(0, 1.0, n), 0, 10)
    sky = sky_from_cloud(pd.Series(f_cloud)).to_numpy()
    rain_obs = obs["rn"].to_numpy() > 0
    pty = np.where(rng.random(n) < 0.85, rain_obs, (f_cloud > 8.5) & (rng.random(n) < 0.3))
    pty = np.where(pty, np.where(obs["ta"].to_numpy() < 0, 3, 1), 0)
    pop = np.select([pty > 0, sky == 4, sky == 3], [80, 50, 20], default=0) + rng.integers(0, 10, n)

    tmp_bias = rng.normal(0, 1.2, len(day_codes))[day_idx]
    base_time = [issued_at_for(d) for d in day]
    return pd.DataFrame({
        "nx": nx, "ny": ny,
        "base_time": base_time,
        "fcst_time": times,
        "tmp": (obs["ta"] + tmp_bias + rng.normal(0, 0.6, n)).round(0),
        "reh": np.clip(obs["hm"] + rng.normal(0, 8, n), 5, 100).round(-1),
        "sky": sky,
        "pty": pty,
        "pop": np.clip(pop, 0, 100),
        "pcp": np.where(pty > 0, np.maximum(obs["rn"], 1.0), 0.0).round(0),
        "wsd": np.clip(obs["ws"] + rng.normal(0, 1.0, n), 0, None).round(1),
    })


def generate(start="2023-01-01", end="2025-12-31", seed=42) -> dict[str, pd.DataFrame]:
    rng = np.random.default_rng(seed)
    times = pd.date_range(pd.Timestamp(start) + pd.Timedelta(hours=1),
                          pd.Timestamp(end) + pd.Timedelta(hours=24), freq="h")
    days = pd.date_range(start, end, freq="D")
    day_index = ((times - pd.Timedelta(hours=1)).normalize() - days[0]).days.to_numpy()

    obs_frames, fcst_frames = [], []

    # 발전소 지점 (제주, 해양성 기후)
    plant_regime = _regimes(days, rng)
    obs, ghi, cloud = _station_weather(times, PLANT.lat, PLANT.lon, 16.5, 9.0, rng, plant_regime, day_index)
    obs.insert(0, "stn_id", PLANT.asos_stn_id)
    obs_frames.append(obs)
    pnx, pny = latlon_to_grid(PLANT.lat, PLANT.lon)
    fcst_frames.append(_forecast_from_obs(obs, cloud, pnx, pny, rng))

    tcell = obs["ta"].to_numpy() + ghi / 800 * 25
    gen = PLANT.capacity_kw * ghi * 1.0 / 1000 * 0.82 * (1 - 0.004 * (tcell - 25))
    gen = np.clip(gen * rng.normal(1, 0.03, len(gen)), 0, PLANT.capacity_kw)
    # 설비 정지일 1%, 결측 0.3%
    outage_days = rng.random(len(days)) < 0.01
    gen[outage_days[day_index]] = 0
    gen[rng.random(len(gen)) < 0.003] = np.nan
    solar = pd.DataFrame({"plant_id": PLANT.id, "target_time": times, "generation_kwh": np.round(gen, 1)})

    # 수요용 5개 도시 (전국 공통 체인에서 약간씩 다르게)
    national_regime = _regimes(days, rng)
    city_ta = []
    for stn, (_, lat, lon) in DEMAND_STATIONS.items():
        regime = np.where(rng.random(len(days)) < 0.8, national_regime, _regimes(days, rng))
        o, _, c = _station_weather(times, lat, lon, 13.5, 13.0, rng, regime, day_index)
        o.insert(0, "stn_id", stn)
        obs_frames.append(o)
        nx, ny = latlon_to_grid(lat, lon)
        fcst_frames.append(_forecast_from_obs(o, c, nx, ny, rng))
        city_ta.append(o["ta"].to_numpy())
    ta_nat = np.mean(city_ta, axis=0)

    start_t = times - pd.Timedelta(hours=1)
    h = start_t.hour.to_numpy()
    profile = 0.86 + 0.10 * np.exp(-((h - 11) ** 2) / 8) + 0.09 * np.exp(-((h - 18) ** 2) / 6) - 0.06 * np.exp(-((h - 4) ** 2) / 6)
    dow = start_t.day_of_week.to_numpy()
    week_factor = np.select([dow == 5, dow == 6], [0.92, 0.86], default=1.0)
    import holidays as _h
    kr = _h.KR(years=range(2020, 2031))
    hol = np.array([d.date() in kr for d in start_t.normalize()])
    week_factor = np.where(hol, 0.82, week_factor)
    years = (start_t - start_t[0]).days.to_numpy() / 365.25
    temp_load = 1100 * np.clip(ta_nat - 24, 0, None) + 750 * np.clip(18 - ta_nat, 0, None)
    ar = np.zeros(len(times))
    for i in range(1, len(ar)):
        ar[i] = 0.97 * ar[i - 1] + rng.normal(0, 0.0025)
    demand = (60000 * profile * week_factor + temp_load * profile) * (1 + 0.012 * years) * (1 + ar)
    demand_df = pd.DataFrame({"target_time": times, "demand_mwh": demand.round(0)})

    plant = pd.DataFrame([{
        "id": PLANT.id, "name": PLANT.name, "capacity_kw": PLANT.capacity_kw,
        "lat": PLANT.lat, "lon": PLANT.lon, "asos_stn_id": PLANT.asos_stn_id, "nx": pnx, "ny": pny,
    }])
    return {
        "plant": plant,
        "weather_observation": pd.concat(obs_frames, ignore_index=True),
        "weather_forecast": pd.concat(fcst_frames, ignore_index=True),
        "solar_actual": solar,
        "demand_actual": demand_df,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", default="2023-01-01")
    ap.add_argument("--end", default="2025-12-31")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    for name, df in generate(args.start, args.end, args.seed).items():
        df.to_csv(PROCESSED_DIR / f"{name}.csv", index=False)
        print(f"{name}: {len(df):,} rows")


if __name__ == "__main__":
    main()
