package kr.solarcast.collect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.Plant;
import kr.solarcast.domain.RunMode;
import kr.solarcast.domain.TargetType;
import kr.solarcast.domain.WeatherForecast;
import kr.solarcast.repository.WeatherForecastRepository;
import kr.solarcast.repository.WeatherObservationRepository;
import kr.solarcast.service.AlertService;
import kr.solarcast.service.EvaluationService;
import kr.solarcast.service.ForecastService;
import kr.solarcast.support.Grids;
import kr.solarcast.support.Grids.Grid;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 라이브 모드 일일 파이프라인 (F1, 기획서 7.2). app.live.enabled=true 이고 KMA 키가 있을 때만 돈다.
 *  01:00 전일 ASOS 관측 수집
 *  17:30 D일 17시 발표 단기예보 수집 (실패하면 14시 발표분 수집 시도)
 *  17:40 내일 24시간 예측 생성 -> 실측이 있는 날은 비교
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivePipeline {

	private static final int RETRIES = 3;

	private final KmaClient kma;
	private final WeatherForecastRepository forecastRepository;
	private final WeatherObservationRepository observationRepository;
	private final ForecastService forecastService;
	private final EvaluationService evaluationService;
	private final AlertService alerts;
	private final AppProperties props;

	private boolean active() {
		if (!props.live().enabled()) {
			return false;
		}
		if (!kma.enabled()) {
			log.warn("라이브 모드인데 KMA_SERVICE_KEY가 없어 수집을 건너뜁니다");
			return false;
		}
		return true;
	}

	@Scheduled(cron = "0 0 1 * * *", zone = "Asia/Seoul")
	public void collectObservations() {
		if (!active()) {
			return;
		}
		LocalDate yesterday = LocalDate.now().minusDays(1);
		List<Integer> stations = new ArrayList<>(Grids.DEMAND_CITIES.stream().map(Grids.City::stnId).toList());
		stations.add(forecastService.plant().getAsosStnId());
		for (int stn : stations) {
			retry("ASOS " + stn, yesterday, () -> {
				var rows = kma.asosHourly(stn, yesterday).stream()
						.filter(o -> !observationRepository.existsByStnIdAndObsTime(o.getStnId(), o.getObsTime()))
						.toList();
				observationRepository.saveAll(rows);
				return rows.size();
			});
		}
	}

	@Scheduled(cron = "0 30 17 * * *", zone = "Asia/Seoul")
	public void collectForecasts() {
		if (!active()) {
			return;
		}
		LocalDate tomorrow = LocalDate.now().plusDays(1);
		Integer saved = retry("단기예보 17시", tomorrow, () -> collect(Times.issuedAt(tomorrow)));
		if (saved == null || saved == 0) {
			retry("단기예보 14시(대체)", tomorrow, () -> collect(Times.fallbackIssuedAt(tomorrow)));
		}
	}

	@Scheduled(cron = "0 40 17 * * *", zone = "Asia/Seoul")
	public void forecastTomorrow() {
		if (!props.live().enabled()) {
			return;
		}
		LocalDate tomorrow = LocalDate.now().plusDays(1);
		for (TargetType t : TargetType.values()) {
			retry(t.key() + " 예측", tomorrow, () -> forecastService.run(t, tomorrow, RunMode.LIVE));
			// 실측이 공개된 과거 날짜는 여기서 함께 비교한다 (발전 실측은 연 단위 공개)
			evaluationService.evaluate(t, LocalDate.now().minusDays(1));
		}
	}

	public int collect(LocalDateTime baseTime) {
		Plant plant = forecastService.plant();
		List<Grid> grids = new ArrayList<>(List.of(new Grid(plant.getNx(), plant.getNy())));
		Grids.DEMAND_CITIES.forEach(c -> grids.add(c.grid()));
		int saved = 0;
		for (Grid g : grids) {
			List<WeatherForecast> rows = kma.vilageForecast(g.nx(), g.ny(), baseTime).stream()
					.filter(w -> !forecastRepository.existsByNxAndNyAndBaseTimeAndFcstTime(w.getNx(), w.getNy(),
							w.getBaseTime(), w.getFcstTime()))
					.toList();
			forecastRepository.saveAll(rows);
			saved += rows.size();
		}
		return saved;
	}

	/** 실패 시 3회 재시도, 그래도 실패하면 alert_log */
	private <T> T retry(String name, LocalDate date, Supplier<T> job) {
		for (int i = 1; i <= RETRIES; i++) {
			try {
				return job.get();
			} catch (RuntimeException e) {
				log.warn("{} 실패 ({}/{}): {}", name, i, RETRIES, e.getMessage());
			}
		}
		alerts.raise(AlertLog.Type.PIPELINE_FAILURE, name + " " + RETRIES + "회 실패", date);
		return null;
	}
}
