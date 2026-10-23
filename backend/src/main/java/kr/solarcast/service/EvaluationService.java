package kr.solarcast.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.solarcast.domain.DailyMetric;
import kr.solarcast.domain.Forecast;
import kr.solarcast.domain.Plant;
import kr.solarcast.domain.TargetType;
import kr.solarcast.domain.WeatherForecast;
import kr.solarcast.repository.DailyMetricRepository;
import kr.solarcast.repository.DemandActualRepository;
import kr.solarcast.repository.ForecastRepository;
import kr.solarcast.repository.SolarActualRepository;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;

/** 예측 vs 실측 비교 (F5). 실측이 있는 날만 daily_metric을 만든다. */
@Service
@RequiredArgsConstructor
public class EvaluationService {

	/** 이용률 10% 미만 시간은 주 지표에서 뺀다 (예측제도 정산 방식) */
	static final double MIN_UTILIZATION = 0.1;
	static final int SKY_CLEAR = 1;

	private final ForecastRepository forecastRepository;
	private final SolarActualRepository solarRepository;
	private final DemandActualRepository demandRepository;
	private final DailyMetricRepository metricRepository;
	private final ForecastService forecastService;

	/** 시각별 최신 예측 (같은 시각에 여러 번 예측했으면 가장 최근 기준 시각) */
	public Map<LocalDateTime, Forecast> latestForecasts(TargetType target, LocalDate date) {
		return forecastRepository.findForRange(target, Times.firstHour(date), Times.lastHour(date)).stream()
				.collect(Collectors.toMap(Forecast::getTargetTime, Function.identity(), (a, b) -> a, LinkedHashMap::new));
	}

	public Map<LocalDateTime, Double> actuals(TargetType target, LocalDate date) {
		LocalDateTime from = Times.firstHour(date), to = Times.lastHour(date);
		Map<LocalDateTime, Double> out = new LinkedHashMap<>();
		if (target == TargetType.SOLAR) {
			solarRepository.findByPlantIdAndTargetTimeBetweenOrderByTargetTime(forecastService.plant().getId(), from, to)
					.forEach(a -> out.put(a.getTargetTime(), a.getGenerationKwh()));
		} else {
			demandRepository.findByTargetTimeBetweenOrderByTargetTime(from, to)
					.forEach(a -> out.put(a.getTargetTime(), a.getDemandMwh()));
		}
		out.values().removeIf(v -> v == null);
		return out;
	}

	@Transactional
	public Optional<DailyMetric> evaluate(TargetType target, LocalDate date) {
		Map<LocalDateTime, Forecast> forecasts = latestForecasts(target, date);
		Map<LocalDateTime, Double> actuals = actuals(target, date);
		if (forecasts.isEmpty() || actuals.size() < 20) {
			return Optional.empty(); // 실측 미공개 (라이브 모드) - 공개 후 일괄 비교
		}
		DailyMetric metric = metricRepository.findByDateAndTargetType(date, target)
				.orElseGet(() -> new DailyMetric(date, target));
		var version = forecasts.values().iterator().next().getModelVersion();
		if (target == TargetType.SOLAR) {
			Plant plant = forecastService.plant();
			Map<LocalDateTime, Integer> sky = forecastService.plantWeather(date, Times.issuedAt(date)).stream()
					.filter(w -> w.getSky() != null)
					.collect(Collectors.toMap(WeatherForecast::getFcstTime, WeatherForecast::getSky, (a, b) -> a));
			metric.update(version, nmae(forecasts, actuals, plant.getCapacityKw()), null,
					clearSkyBelowP10(forecasts, actuals, sky, plant.getCapacityKw()));
		} else {
			metric.update(version, null, mape(forecasts, actuals), 0);
		}
		return Optional.of(metricRepository.save(metric));
	}

	static Double nmae(Map<LocalDateTime, Forecast> f, Map<LocalDateTime, Double> a, double capacity) {
		List<Double> errs = a.entrySet().stream()
				.filter(e -> f.containsKey(e.getKey()) && e.getValue() / capacity >= MIN_UTILIZATION)
				.map(e -> Math.abs(f.get(e.getKey()).getP50() - e.getValue()) / capacity * 100)
				.toList();
		return errs.isEmpty() ? null : errs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
	}

	static Double mape(Map<LocalDateTime, Forecast> f, Map<LocalDateTime, Double> a) {
		return a.entrySet().stream()
				.filter(e -> f.containsKey(e.getKey()) && e.getValue() > 0)
				.mapToDouble(e -> Math.abs(f.get(e.getKey()).getP50() - e.getValue()) / e.getValue() * 100)
				.average().stream().boxed().findFirst().orElse(null);
	}

	/** 맑음 예보 + 발전이 있어야 할 시간에 실측이 P10 아래인 연속 시간의 최대 길이 (출력 이상 의심) */
	static int clearSkyBelowP10(Map<LocalDateTime, Forecast> f, Map<LocalDateTime, Double> a,
			Map<LocalDateTime, Integer> sky, double capacity) {
		int run = 0, max = 0;
		for (var e : f.entrySet()) {
			Double actual = a.get(e.getKey());
			Forecast p = e.getValue();
			boolean suspicious = actual != null && Integer.valueOf(SKY_CLEAR).equals(sky.get(e.getKey()))
					&& p.getP50() >= capacity * MIN_UTILIZATION && actual < p.getP10();
			run = suspicious ? run + 1 : 0;
			max = Math.max(max, run);
		}
		return max;
	}
}
