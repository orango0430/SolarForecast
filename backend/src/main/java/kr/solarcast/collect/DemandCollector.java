package kr.solarcast.collect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.DemandActual;
import kr.solarcast.repository.DemandActualRepository;
import kr.solarcast.service.AlertService;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전국 수요 실측을 전력거래소 수급현황으로 채운다.
 * 마지막 실측 다음 날부터 어제까지 비어 있는 시간을 채우며, 사이트가 최근 1년까지만 주므로 그 이전은 건너뛴다.
 * 수요 예측의 라그 피처(7일 전, 2~8일 전 평균)에 쓰인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandCollector {

	static final int MAX_BACK_DAYS = 364;
	static final int CHUNK_DAYS = 31;

	private final KpxSukubClient kpx;
	private final DemandActualRepository repository;
	private final AlertService alerts;
	private final AppProperties props;

	public record SyncResult(LocalDate from, LocalDate to, int fetched, int saved) {
	}

	/** 17:40 예측 전에 어제까지 채운다. 00:20에 한 번 더 (전날 마지막 시간 포함) */
	@Scheduled(cron = "0 35 17 * * *", zone = "Asia/Seoul")
	@Scheduled(cron = "0 20 0 * * *", zone = "Asia/Seoul")
	public void scheduled() {
		if (!props.live().enabled()) {
			return;
		}
		try {
			log.info("수요 실측 동기화: {}", syncUntil(LocalDate.now().minusDays(1)));
		} catch (RuntimeException e) {
			alerts.raise(AlertLog.Type.PIPELINE_FAILURE, "전력거래소 수요 수집 실패: " + e.getMessage(), LocalDate.now());
		}
	}

	/** 마지막 실측 다음 날 ~ to 까지 채운다 */
	public SyncResult syncUntil(LocalDate to) {
		LocalDateTime last = repository.findMaxTargetTime();
		LocalDate earliest = LocalDate.now().minusDays(MAX_BACK_DAYS);
		LocalDate from = last == null ? earliest : Times.dateOf(last).plusDays(1);
		if (from.isBefore(earliest)) {
			from = earliest;
		}
		return sync(from, to);
	}

	public SyncResult sync(LocalDate from, LocalDate to) {
		int fetched = 0, saved = 0;
		for (LocalDate s = from; !s.isAfter(to); s = s.plusDays(CHUNK_DAYS)) {
			LocalDate e = s.plusDays(CHUNK_DAYS - 1).isAfter(to) ? to : s.plusDays(CHUNK_DAYS - 1);
			// 하루의 24시(다음날 00:00) 값까지 만들려면 다음날 00:00 샘플이 필요하다
			List<KpxSukubClient.HourlyDemand> rows = kpx.hourly(s, e.plusDays(1));
			LocalDateTime lo = Times.firstHour(s), hi = Times.lastHour(e);
			rows = rows.stream().filter(r -> !r.targetTime().isBefore(lo) && !r.targetTime().isAfter(hi)).toList();
			fetched += rows.size();
			saved += save(rows, lo, hi);
		}
		return new SyncResult(from, to, fetched, saved);
	}

	int save(List<KpxSukubClient.HourlyDemand> rows, LocalDateTime lo, LocalDateTime hi) {
		Set<LocalDateTime> existing = new HashSet<>();
		repository.findByTargetTimeBetweenOrderByTargetTime(lo, hi).forEach(d -> existing.add(d.getTargetTime()));
		List<DemandActual> fresh = rows.stream()
				.filter(r -> !existing.contains(r.targetTime()))
				.map(r -> new DemandActual(null, r.targetTime(), r.demandMwh()))
				.toList();
		repository.saveAll(fresh);
		return fresh.size();
	}

	public Map<String, Object> status() {
		LocalDateTime last = repository.findMaxTargetTime();
		return Map.of("lastActual", String.valueOf(last), "source", "openapi.kpx.or.kr/sukub.do (5분 현재수요 → 시간 평균)");
	}
}
