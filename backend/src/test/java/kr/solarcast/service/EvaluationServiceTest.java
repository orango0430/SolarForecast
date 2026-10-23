package kr.solarcast.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.solarcast.domain.DailyMetric;
import kr.solarcast.domain.Forecast;
import kr.solarcast.domain.TargetType;

class EvaluationServiceTest {

	private static final LocalDateTime T = LocalDateTime.of(2025, 10, 1, 12, 0);

	private static Forecast f(double p10, double p50, double p90) {
		return Forecast.builder().p10(p10).p50(p50).p90(p90).build();
	}

	@Test
	void nmaeSkipsLowUtilizationHours() {
		Map<LocalDateTime, Forecast> fc = new LinkedHashMap<>();
		fc.put(T, f(0, 500, 600));
		fc.put(T.plusHours(1), f(0, 50, 60));
		Map<LocalDateTime, Double> act = Map.of(T, 400.0, T.plusHours(1), 0.0);
		// 이용률 10% 미만(0kWh) 시간은 빠지고 |500-400|/1000 = 10%
		assertThat(EvaluationService.nmae(fc, act, 1000)).isEqualTo(10.0);
	}

	@Test
	void mapeAverageOfPercentErrors() {
		Map<LocalDateTime, Forecast> fc = Map.of(T, f(0, 110, 0), T.plusHours(1), f(0, 90, 0));
		Map<LocalDateTime, Double> act = Map.of(T, 100.0, T.plusHours(1), 100.0);
		assertThat(EvaluationService.mape(fc, act)).isEqualTo(10.0);
	}

	@Test
	void clearSkyBelowP10CountsLongestRun() {
		Map<LocalDateTime, Forecast> fc = new LinkedHashMap<>();
		Map<LocalDateTime, Double> act = new LinkedHashMap<>();
		Map<LocalDateTime, Integer> sky = new LinkedHashMap<>();
		for (int h = 0; h < 5; h++) {
			fc.put(T.plusHours(h), f(400, 500, 600));
			act.put(T.plusHours(h), h == 2 ? 450.0 : 100.0);
			sky.put(T.plusHours(h), 1);
		}
		assertThat(EvaluationService.clearSkyBelowP10(fc, act, sky, 1000)).isEqualTo(2);
		sky.replaceAll((k, v) -> 4); // 흐림 예보면 날씨 탓이라 이상 아님
		assertThat(EvaluationService.clearSkyBelowP10(fc, act, sky, 1000)).isZero();
	}

	@Test
	void movingAveragesNewestFirst() {
		List<DailyMetric> recent = new java.util.ArrayList<>();
		for (int i = 0; i < 9; i++) {
			DailyMetric m = new DailyMetric(LocalDate.of(2025, 10, 9).minusDays(i), TargetType.SOLAR);
			m.update(null, i < 3 ? 20.0 : 5.0, null, 0);
			recent.add(m);
		}
		List<Double> avg = MonitoringService.movingAverages(recent, 7);
		assertThat(avg).hasSize(3);
		assertThat(avg.get(0)).isEqualTo((3 * 20.0 + 4 * 5.0) / 7);
		assertThat(avg.get(2)).isEqualTo((20.0 + 6 * 5.0) / 7);
	}
}
