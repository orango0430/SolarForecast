import numpy as np
import pandas as pd

from solarcast.features import _calendar, day_target_times, issued_at_for, sky_from_cloud, solar_features
from solarcast.grid import latlon_to_grid


def test_sky_from_cloud_bins():
    s = sky_from_cloud(pd.Series([0, 5, 6, 8, 9, 10]))
    assert s.tolist() == [1, 1, 3, 3, 4, 4]


def test_grid_known_points():
    # 기상청 활용가이드 예시: 서울 종로구 (60, 127)
    assert latlon_to_grid(37.5714, 126.9658) == (60, 127)


def test_hour_ending_calendar():
    cal = _calendar(pd.Series(pd.to_datetime(["2025-01-01 01:00", "2025-01-02 00:00"])))
    assert cal["hour"].tolist() == [1, 24]
    assert (cal["date"] == pd.Timestamp("2025-01-01")).all()


def test_issue_time_and_targets():
    assert issued_at_for(pd.Timestamp("2025-06-02")) == pd.Timestamp("2025-06-01 17:00")
    t = day_target_times(pd.Timestamp("2025-06-02"))
    assert len(t) == 24 and t[0] == pd.Timestamp("2025-06-02 01:00") and t[-1] == pd.Timestamp("2025-06-03 00:00")


def test_solar_night_has_no_clearsky():
    times = day_target_times(pd.Timestamp("2025-06-21"))
    w = pd.DataFrame({"target_time": times, "tmp": 20.0, "reh": 60.0, "sky": 1, "pty": 0, "wsd": 2.0})
    X = solar_features(w, 33.27, 126.20)
    assert X.loc[X["hour"].isin([1, 2, 3, 23, 24]), "clearsky_ghi"].eq(0).all()
    assert X.loc[X["hour"] == 13, "clearsky_ghi"].iloc[0] > 800
    assert np.isfinite(X.drop(columns=["target_time"]).to_numpy(dtype=float)).all()
