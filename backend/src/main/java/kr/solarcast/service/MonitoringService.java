package kr.solarcast.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.DailyMetric;
import kr.solarcast.domain.ModelVersion;
import kr.solarcast.domain.TargetType;
import kr.solarcast.repository.AlertLogRepository;
import kr.solarcast.repository.DailyMetricRepository;
import kr.solarcast.repository.ModelVersionRepository;
import lombok.RequiredArgsConstructor;

/**
 * 감시 규칙 (기획서 7.3).
 * - 일별 오차의 7일 이동평균이 검증 성능의 1.5배 초과 -> 경고, 3일 연속이면 재학습 권고
 * - 맑음 예보인데 실측이 P10 아래인 시간이 3시간 이상 연속 -> 설비 이상 의심
 */
@Service
@RequiredArgsConstructor
public class MonitoringService {

	static final int ANOMALY_HOURS = 3;

	private final DailyMetricRepository metricRepository;
	private final ModelVersionRepository modelRepository;
	private final AlertLogRepository alertRepository;
	private final AlertService alerts;
	private final AppProperties props;

	public record Status(Double movingAverage, Double threshold, int exceededDays) {
	}

	public Status check(TargetType target, LocalDate date) {
		var cfg = props.monitor();
		Double validation = modelRepository.findFirstByTargetTypeAndStage(target, "champion")
				.map(ModelVersion::getValidationScore).orElse(null);
		List<DailyMetric> recent = metricRepository.findByTargetTypeAndDateLessThanEqualOrderByDateDesc(
				target, date, PageRequest.of(0, cfg.windowDays() + cfg.consecutiveDays()));

		if (target == TargetType.SOLAR && !recent.isEmpty() && recent.get(0).getDate().equals(date)
				&& recent.get(0).getBelowP10Hours() >= ANOMALY_HOURS) {
			alerts.raise(AlertLog.Type.OUTPUT_ANOMALY, "맑음 예보인데 실측이 예측 하한(P10)보다 낮은 시간이 "
					+ recent.get(0).getBelowP10Hours() + "시간 연속 - 설비 이상 의심", date);
		}
		if (validation == null || recent.size() < cfg.windowDays()) {
			return new Status(null, null, 0);
		}
		double threshold = validation * cfg.ratioThreshold();
		List<Double> averages = movingAverages(recent, cfg.windowDays());
		int exceeded = 0;
		for (double avg : averages) { // averages[0] = 오늘 기준
			if (avg <= threshold) {
				break;
			}
			exceeded++;
		}
		Double today = averages.isEmpty() ? null : averages.get(0);
		if (exceeded == 1) { // 넘기 시작한 날만 알린다 (매일 반복 알림 방지)
			alerts.raise(AlertLog.Type.PERFORMANCE, String.format("%s 7일 평균 오차 %.2f%% > 기준 %.2f%% (%d일째)",
					target.key(), today, threshold, exceeded), date);
		}
		if (exceeded >= cfg.consecutiveDays()
				&& !alertRepository.existsByTypeAndTargetDateBetween(AlertLog.Type.RETRAIN_RECOMMENDED,
						date.minusDays(cfg.windowDays()), date)) {
			alerts.raise(AlertLog.Type.RETRAIN_RECOMMENDED,
					target.key() + " 성능 경고 " + exceeded + "일 연속 - 재학습 후 challenger 등록 권고", date);
		}
		return new Status(today, threshold, exceeded);
	}

	/** recent는 날짜 내림차순. i번째 값 = i일 전 기준 window일 평균 */
	static List<Double> movingAverages(List<DailyMetric> recent, int window) {
		List<Double> out = new ArrayList<>();
		for (int i = 0; i + window <= recent.size(); i++) {
			out.add(recent.subList(i, i + window).stream()
					.map(DailyMetric::score).filter(s -> s != null)
					.mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
		}
		return out;
	}
}
