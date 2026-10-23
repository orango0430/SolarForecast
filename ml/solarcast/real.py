"""실데이터 수집·변환.

- 태양광 실측: 공공데이터포털 15095374 한국남부발전 무릉리 태양광발전실적 (data/raw/murung_solar.csv)
- 전력수요 실측: 공공데이터포털 15065266 한국전력거래소 시간별 전국 전력수요량 (data/raw/kpx_demand.csv)
- 관측 기상: Open-Meteo Historical Weather API (ERA5 재분석) - 기상청 ASOS 키 승인 전 대체
- 예보 기상: Open-Meteo Previous Runs API, JMA 모델의 하루 전(previous_day1) 예보
             - 기상청 단기예보 과거자료 대체. 발표 시각이 D-1 17시와 정확히 같지는 않다.

원본 응답은 data/raw/openmeteo/에 그대로 저장하고 재사용한다 (호출 한도 대응).

    python -m solarcast.real fetch
    python -m solarcast.real build
"""
import argparse
import json
import time

import numpy as np
import pandas as pd
import requests

from .config import DEMAND_STATIONS, PLANT, PROCESSED_DIR, RAW_DIR
from .features import issued_at_for
from .grid import latlon_to_grid

START, END = "2021-05-01", "2025-12-31"
OM_DIR = RAW_DIR / "openmeteo"

OBS_URL = "https://archive-api.open-meteo.com/v1/archive"
OBS_VARS = ["temperature_2m", "relative_humidity_2m", "precipitation", "wind_speed_10m",
            "cloud_cover", "shortwave_radiation", "sunshine_duration"]
FCST_URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
FCST_VARS = ["temperature_2m", "relative_humidity_2m", "precipitation", "wind_speed_10m", "cloud_cover"]
FCST_MODEL = "jma_seamless"


def sites() -> dict[int, tuple[float, float]]:
    s = {PLANT.asos_stn_id: (PLANT.lat, PLANT.lon)}
    s.update({stn: (lat, lon) for stn, (_, lat, lon) in DEMAND_STATIONS.items()})
    return s


def _year_ranges():
    for y in range(int(START[:4]), int(END[:4]) + 1):
        yield max(START, f"{y}-01-01"), min(END, f"{y}-12-31")


def _get(url, params, path):
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    for attempt in range(4):
        r = requests.get(url, params=params, timeout=120)
        # 200이어도 본문이 스트리밍 오류 문자열인 경우가 있어 내용까지 확인한다
        if r.status_code == 200 and r.text.lstrip().startswith("{") and "hourly" in r.text:
            path.write_text(r.text, encoding="utf-8")
            return r.json()
        time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"open-meteo 실패 {r.status_code}: {r.text[:200]}")


def fetch():
    OM_DIR.mkdir(parents=True, exist_ok=True)
    for stn, (lat, lon) in sites().items():
        for s, e in _year_ranges():
            common = dict(latitude=lat, longitude=lon, start_date=s, end_date=e,
                          timezone="Asia/Seoul", wind_speed_unit="ms")
            _get(OBS_URL, {**common, "hourly": ",".join(OBS_VARS)}, OM_DIR / f"obs_{stn}_{s[:4]}.json")
            _get(FCST_URL, {**common, "models": FCST_MODEL,
                            "hourly": ",".join(f"{v}_previous_day1" for v in FCST_VARS)},
                 OM_DIR / f"fcst_{stn}_{s[:4]}.json")
            print(f"{stn} {s[:4]} ok")


def _load_hourly(kind, stn) -> pd.DataFrame:
    frames = []
    for s, _ in _year_ranges():
        js = json.loads((OM_DIR / f"{kind}_{stn}_{s[:4]}.json").read_text(encoding="utf-8"))
        frames.append(pd.DataFrame(js["hourly"]))
    df = pd.concat(frames, ignore_index=True)
    df["time"] = pd.to_datetime(df["time"])
    df.columns = [c.replace("_previous_day1", "") for c in df.columns]
    # Open-Meteo의 강수·일사는 '직전 1시간' 값이라 hour-ending과 같다. 하루의 1~24시를 맞추려 00시를 전날 24시로 본다.
    return df[df["time"] > pd.Timestamp(START)].reset_index(drop=True)


def build_weather():
    obs_frames, fcst_frames = [], []
    for stn, (lat, lon) in sites().items():
        o = _load_hourly("obs", stn)
        obs_frames.append(pd.DataFrame({
            "stn_id": stn,
            "obs_time": o["time"],
            "ta": o["temperature_2m"],
            "hm": o["relative_humidity_2m"],
            "rn": o["precipitation"],
            "ws": o["wind_speed_10m"],
            "dc10_tca": (o["cloud_cover"] / 10).round(),
            "ss": (o["sunshine_duration"] / 3600).round(2),
            "icsr": (o["shortwave_radiation"] * 0.0036).round(3),  # W/m2 -> MJ/m2/h
        }))

        f = _load_hourly("fcst", stn).dropna(subset=["temperature_2m"])
        nx, ny = latlon_to_grid(lat, lon)
        cloud10 = (f["cloud_cover"] / 10).round()
        sky = pd.cut(cloud10.fillna(0), bins=[-1, 5, 8, 10], labels=[1, 3, 4]).astype(int)
        rain = f["precipitation"].fillna(0) >= 0.1
        pty = np.where(rain, np.where(f["temperature_2m"] < 0, 3, 1), 0)
        fcst_frames.append(pd.DataFrame({
            "nx": nx, "ny": ny,
            "base_time": [issued_at_for(t - pd.Timedelta(hours=1)) for t in f["time"]],
            "fcst_time": f["time"],
            "tmp": f["temperature_2m"].round(1),
            "reh": f["relative_humidity_2m"].round(0),
            "sky": sky,
            "pty": pty,
            "pop": np.nan,  # 과거 예보 아카이브에 강수확률이 없어 피처에서 제외
            "pcp": f["precipitation"].round(1),
            "wsd": f["wind_speed_10m"].round(1),
        }))
    return pd.concat(obs_frames, ignore_index=True), pd.concat(fcst_frames, ignore_index=True)


def _wide_to_long(df, date_col, hour_cols, value_name):
    long = df.melt(id_vars=[date_col], value_vars=hour_cols, var_name="h", value_name=value_name)
    hour = long["h"].astype(str).str.extract(r"(\d+)")[0].astype(int)
    long["target_time"] = pd.to_datetime(long[date_col]) + pd.to_timedelta(hour, unit="h")
    return long[["target_time", value_name]].sort_values("target_time").reset_index(drop=True)


def build_actuals():
    s = pd.read_csv(RAW_DIR / "murung_solar.csv", encoding="utf-8-sig")
    hours = [str(h) for h in range(1, 25)]
    # 호기가 여럿이면 일자별 합산 (현재 데이터는 1호기뿐)
    s = s.groupby("년월일", as_index=False)[hours].sum()
    solar = _wide_to_long(s, "년월일", hours, "generation_kwh")
    solar.insert(0, "plant_id", PLANT.id)

    d = pd.read_csv(RAW_DIR / "kpx_demand.csv", encoding="utf-8-sig")
    demand = _wide_to_long(d, "date", [f"h{h}" for h in range(1, 25)], "demand_mwh")

    lo, hi = pd.Timestamp(START), pd.Timestamp(END) + pd.Timedelta(hours=24)
    solar = solar[(solar["target_time"] > lo) & (solar["target_time"] <= hi)]
    demand = demand[(demand["target_time"] > lo) & (demand["target_time"] <= hi)]
    return solar, demand


def build():
    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    obs, fcst = build_weather()
    solar, demand = build_actuals()
    nx, ny = latlon_to_grid(PLANT.lat, PLANT.lon)
    plant = pd.DataFrame([{"id": PLANT.id, "name": PLANT.name, "capacity_kw": PLANT.capacity_kw,
                           "lat": PLANT.lat, "lon": PLANT.lon, "asos_stn_id": PLANT.asos_stn_id,
                           "nx": nx, "ny": ny}])
    for name, df in {"plant": plant, "weather_observation": obs, "weather_forecast": fcst,
                     "solar_actual": solar, "demand_actual": demand}.items():
        df.to_csv(PROCESSED_DIR / f"{name}.csv", index=False)
        print(f"{name}: {len(df):,} rows")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=["fetch", "build", "all"])
    a = ap.parse_args()
    if a.step in ("fetch", "all"):
        fetch()
    if a.step in ("build", "all"):
        build()


if __name__ == "__main__":
    main()
