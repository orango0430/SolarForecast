package kr.solarcast.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.ReplayState;
import kr.solarcast.domain.RunMode;
import kr.solarcast.domain.TargetType;
import kr.solarcast.repository.DemandActualRepository;
import kr.solarcast.repository.ReplayStateRepository;
import kr.solarcast.repository.SolarActualRepository;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 리플레이 모드 (F6). 학습에 쓰지 않은 마지막 구간을 하루씩 재생하며
 * 예보 확인 -> 예측 -> 실측 비교 -> 감시를 매일 도는 것처럼 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplayService {

	public static final int DEFAULT_DAYS = 90;

	private final ReplayStateRepository stateRepository;
	private final SolarActualRepository solarRepository;
	private final DemandActualRepository demandRepository;
	private final ForecastService forecastService;
	private final EvaluationService evaluationService;
	private final MonitoringService monitoringService;
	private final AlertService alerts;
	private final AppProperties props;

	public record StepResult(LocalDate date, List<String> log, boolean ok) {
	}

	public ReplayState state() {
		return stateRepository.findById(ReplayState.SINGLETON_ID).orElseGet(ReplayState::new);
	}

	/** 두 실측이 모두 있는 마지막 날 기준으로 기본 리플레이 구간 */
	public LocalDate[] defaultRange() {
		LocalDateTime s = solarRepository.findMaxTargetTime(), d = demandRepository.findMaxTargetTime();
		if (s == null || d == null) {
			throw new IllegalStateException("실측 데이터가 없습니다");
		}
		LocalDate end = Times.dateOf(s.isBefore(d) ? s : d);
		return new LocalDate[] {end.minusDays(DEFAULT_DAYS - 1), end};
	}

	@Transactional
	public ReplayState start(LocalDate from, LocalDate to, boolean auto) {
		LocalDate[] range = defaultRange();
		ReplayState state = state();
		state.start(from != null ? from : range[0], to != null ? to : range[1], auto);
		return stateRepository.save(state);
	}

	@Transactional
	public ReplayState setAuto(boolean auto) {
		ReplayState state = state();
		state.setAuto(auto);
		return stateRepository.save(state);
	}

	@Transactional
	public ReplayState stop() {
		ReplayState state = state();
		state.stop();
		return stateRepository.save(state);
	}

	/** 하루 재생. 예측 단계가 실패하면 커서를 넘기지 않고 경고를 남긴다. */
	public synchronized StepResult step() {
		ReplayState state = state();
		if (state.getCursor() == null || state.finished()) {
			return new StepResult(null, List.of("리플레이가 시작되지 않았거나 끝났습니다"), false);
		}
		LocalDate date = state.getCursor();
		List<String> log = new ArrayList<>();
		boolean ok = true;
		for (TargetType target : TargetType.values()) {
			try {
				var r = forecastService.run(target, date, RunMode.REPLAY);
				log.add(target.key() + " 예측 " + r.hours() + "건 (v" + r.modelVersion() + (r.fallback() ? ", 대체" : "") + ")");
				evaluationService.evaluate(target, date).ifPresent(m -> log.add(target.key() + " 오차 "
						+ String.format("%.2f%%", m.score())));
				var status = monitoringService.check(target, date);
				if (status.movingAverage() != null) {
					log.add(String.format("%s 7일 평균 %.2f%% / 기준 %.2f%%", target.key(), status.movingAverage(),
							status.threshold()));
				}
			} catch (RuntimeException e) {
				ok = false;
				log.add(target.key() + " 실패: " + e.getMessage());
				alerts.raise(AlertLog.Type.PIPELINE_FAILURE, target.key() + " 리플레이 실패: " + e.getMessage(), date);
			}
		}
		if (ok) {
			state.advance();
		} else {
			state.stop(); // 실패한 날에서 멈추고 사람이 확인
		}
		stateRepository.save(state);
		return new StepResult(date, log, ok);
	}

	@Scheduled(fixedDelayString = "${app.replay.auto-interval-ms:3000}")
	public void autoTick() {
		ReplayState state = state();
		if (state.isRunning() && state.isAuto()) {
			StepResult r = step();
			log.info("replay {} {}", r.date(), r.log());
		}
	}

	public Map<String, Object> status() {
		ReplayState s = state();
		return Map.of(
				"start", String.valueOf(s.getStartDate()),
				"end", String.valueOf(s.getEndDate()),
				"cursor", String.valueOf(s.getCursor()),
				"running", s.isRunning(),
				"auto", s.isAuto(),
				"intervalMs", props.replay().autoIntervalMs());
	}
}
