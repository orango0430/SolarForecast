package kr.solarcast.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import kr.solarcast.client.ModelApiClient;
import kr.solarcast.client.ModelApiDtos.HistoryRow;
import kr.solarcast.client.ModelApiDtos.PredictRequest;
import kr.solarcast.client.ModelApiDtos.PredictResponse;
import kr.solarcast.client.ModelApiDtos.WeatherRow;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.Forecast;
import kr.solarcast.domain.ModelVersion;
import kr.solarcast.domain.Plant;
import kr.solarcast.domain.RunMode;
import kr.solarcast.domain.TargetType;
import kr.solarcast.domain.WeatherForecast;
import kr.solarcast.repository.DemandActualRepository;
import kr.solarcast.repository.ForecastRepository;
import kr.solarcast.repository.PlantRepository;
import kr.solarcast.repository.WeatherForecastRepository;
import kr.solarcast.support.Grids;
import kr.solarcast.support.Grids.Grid;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * D일 1~24시 예측 생성 (F2). 기준 시각은 D-1일 17시.
 * 17시 발표 예보가 없으면 14시 발표분으로 대체하고 표시한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForecastService {

	static final double TMP_MIN = -30, TMP_MAX = 45;

	private final WeatherForecastRepository weatherRepository;
	private final DemandActualRepository demandRepository;
	private final ForecastRepository forecastRepository;
	private final PlantRepository plantRepository;
	private final ModelApiClient modelApi;
	private final ModelSyncService modelSync;
	private final AlertService alerts;
	private final MeterRegistry meters;

	public record Result(TargetType target, LocalDate date, String modelVersion, boolean fallback, int hours) {
	}

	public static class ForecastFailedException extends RuntimeException {
		public ForecastFailedException(String message) {
			super(message);
		}
	}

	@Transactional
	public Result run(TargetType target, LocalDate date, RunMode mode) {
		LocalDateTime issuedAt = Times.issuedAt(date);
		boolean fallback = false;
		List<WeatherRow> weather = collectWeather(target, date, issuedAt);
		if (weather.isEmpty()) {
			weather = collectWeather(target, date, Times.fallbackIssuedAt(date));
			fallback = !weather.isEmpty();
			if (fallback) {
				alerts.raise(AlertLog.Type.FALLBACK_FORECAST,
						target.key() + " 17시 예보 없음, 14시 발표분으로 대체 예측", date);
			}
		}
		if (weather.isEmpty()) {
			alerts.raise(AlertLog.Type.DATA_MISSING, target.key() + " 예보 기상 수집 실패, 예측 불가", date);
			throw new ForecastFailedException(date + " 예보 기상이 없습니다");
		}
		weather = checkRange(weather, date);

		List<HistoryRow> history = target == TargetType.DEMAND ? demandHistory(date, issuedAt) : List.of();
		PredictRequest req = new PredictRequest(target.key(), date, weather, history, issuedAt);

		var timer = meters.timer("solarcast.forecast.predict", "target", target.key());
		PredictResponse res = timer.record(() -> modelApi.predict(req));
		ModelVersion version = modelSync.resolve(target, res.modelVersion());

		// 같은 기준 시각으로 다시 돌리면 덮어쓴다 (재실행 멱등)
		forecastRepository.deleteIssued(target, issuedAt, Times.firstHour(date), Times.lastHour(date));
		LocalDateTime now = LocalDateTime.now();
		boolean usedFallback = fallback;
		List<Forecast> rows = res.predictions().stream().map(p -> Forecast.builder()
				.targetType(target)
				.targetTime(p.targetTime())
				.issuedAt(issuedAt)
				.p10(p.p10()).p50(p.p50()).p90(p.p90())
				.modelVersion(version)
				.fallback(usedFallback)
				.mode(mode)
				.createdAt(now)
				.build()).toList();
		forecastRepository.saveAll(rows);
		meters.counter("solarcast.forecast.runs", "target", target.key(), "mode", mode.name()).increment();
		return new Result(target, date, res.modelVersion(), fallback, rows.size());
	}

	public List<WeatherForecast> plantWeather(LocalDate date, LocalDateTime baseTime) {
		Plant plant = plant();
		return weatherRepository.findByNxAndNyAndBaseTimeAndFcstTimeBetweenOrderByFcstTime(
				plant.getNx(), plant.getNy(), baseTime, Times.firstHour(date), Times.lastHour(date));
	}

	private List<WeatherRow> collectWeather(TargetType target, LocalDate date, LocalDateTime baseTime) {
		List<Grid> grids = target == TargetType.SOLAR
				? List.of(new Grid(plant().getNx(), plant().getNy()))
				: Grids.DEMAND_CITIES.stream().map(Grids.City::grid).toList();
		List<WeatherRow> rows = new ArrayList<>();
		for (Grid g : grids) {
			List<WeatherForecast> list = weatherRepository.findByNxAndNyAndBaseTimeAndFcstTimeBetweenOrderByFcstTime(
					g.nx(), g.ny(), baseTime, Times.firstHour(date), Times.lastHour(date));
			if (list.size() < 24) {
				if (target == TargetType.SOLAR) {
					return List.of();
				}
				log.warn("{} 격자({},{}) 예보 {}건만 있음", date, g.nx(), g.ny(), list.size());
				continue;
			}
			list.forEach(w -> rows.add(new WeatherRow(w.getFcstTime(), w.getTmp(), w.getReh(), w.getSky(), w.getPty(),
					w.getWsd())));
		}
		return rows;
	}

	/** 기온 범위를 벗어난 값은 제외하고 경고. 태양광은 앞 시각 값으로 채운다. */
	List<WeatherRow> checkRange(List<WeatherRow> rows, LocalDate date) {
		List<WeatherRow> out = new ArrayList<>();
		int bad = 0;
		WeatherRow prev = null;
		for (WeatherRow r : rows) {
			if (r.tmp() == null || r.tmp() < TMP_MIN || r.tmp() > TMP_MAX) {
				bad++;
				if (prev != null && prev.fcstTime().plusHours(1).equals(r.fcstTime())) {
					out.add(new WeatherRow(r.fcstTime(), prev.tmp(), r.reh(), r.sky(), r.pty(), r.wsd()));
				}
				continue;
			}
			out.add(r);
			prev = r;
		}
		if (bad > 0) {
			alerts.raise(AlertLog.Type.DATA_RANGE, "기온 범위(" + TMP_MIN + "~" + TMP_MAX + ") 이탈 " + bad + "건 제외", date);
		}
		return out;
	}

	/** D-8일 01시 ~ 기준 시각까지의 수요 실측 (기준 시각 이후 값은 보내지 않는다) */
	private List<HistoryRow> demandHistory(LocalDate date, LocalDateTime issuedAt) {
		return demandRepository.findByTargetTimeBetweenOrderByTargetTime(Times.firstHour(date.minusDays(8)), issuedAt)
				.stream()
				.filter(d -> d.getDemandMwh() != null)
				.map(d -> new HistoryRow(d.getTargetTime(), d.getDemandMwh()))
				.toList();
	}

	public Plant plant() {
		return plantRepository.findAll().stream().findFirst()
				.orElseThrow(() -> new IllegalStateException("plant 데이터가 없습니다. 시드 데이터를 확인하세요"));
	}
}
