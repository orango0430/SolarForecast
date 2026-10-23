"""누수 점검: 예측 시점(D일 17시) 이후의 데이터가 피처에 섞이지 않는지."""
import numpy as np
import pandas as pd

from solarcast.features import day_target_times, demand_features, demand_lag_features, issued_at_for


def _history(end="2025-06-10"):
    idx = pd.date_range("2025-05-01 01:00", end, freq="h")
    return pd.Series(np.arange(len(idx), dtype=float), index=idx)


def test_future_history_does_not_change_features():
    target = pd.Timestamp("2025-06-02")
    issued = issued_at_for(target)
    times = pd.Series(day_target_times(target))
    full = _history()
    truncated = full[full.index <= issued]
    a = demand_lag_features(times, full, issued)
    b = demand_lag_features(times, truncated, issued)
    pd.testing.assert_frame_equal(a, b)


def test_poisoned_future_values_are_ignored():
    target = pd.Timestamp("2025-06-02")
    issued = issued_at_for(target)
    times = pd.Series(day_target_times(target))
    hist = _history()
    poisoned = hist.copy()
    poisoned[poisoned.index > issued] = 1e9
    pd.testing.assert_frame_equal(demand_lag_features(times, hist, issued),
                                  demand_lag_features(times, poisoned, issued))


def test_lags_only_use_times_before_issue():
    target = pd.Timestamp("2025-06-02")
    issued = issued_at_for(target)
    for t in day_target_times(target):
        used = [t - pd.Timedelta(days=k) for k in [7, *range(2, 9)]]
        assert max(used) <= issued


def test_training_shortcut_equals_per_day_issue():
    """dataset.demand_training_frame은 issued_at을 전체 끝으로 둔다. 하루씩 잘라 만든 값과 같아야 한다."""
    hist = _history()
    target = pd.Timestamp("2025-06-05")
    times = day_target_times(target)
    w = pd.DataFrame({"target_time": times, "tmp": 25.0, "reh": 50.0})
    once = demand_features(w, hist, hist.index.max())
    per_day = demand_features(w, hist, issued_at_for(target))
    pd.testing.assert_frame_equal(once, per_day)
