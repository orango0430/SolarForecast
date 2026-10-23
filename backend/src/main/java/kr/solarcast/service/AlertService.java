package kr.solarcast.service;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.MeterRegistry;
import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.repository.AlertLogRepository;
import lombok.extern.slf4j.Slf4j;

/** alert_log 저장 + Slack Incoming Webhook (설정돼 있을 때만). */
@Slf4j
@Service
public class AlertService {

	private final AlertLogRepository repository;
	private final MeterRegistry meters;
	private final RestClient slack;
	private final String webhookUrl;

	public AlertService(AlertLogRepository repository, MeterRegistry meters, RestClient.Builder builder,
			AppProperties props) {
		this.repository = repository;
		this.meters = meters;
		this.slack = builder.build();
		this.webhookUrl = props.slack().webhookUrl();
	}

	/** 호출한 쪽 트랜잭션이 롤백돼도 경고는 남아야 한다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AlertLog raise(AlertLog.Type type, String message, LocalDate targetDate) {
		log.warn("[ALERT {}] {} ({})", type, message, targetDate);
		meters.counter("solarcast.alerts", "type", type.name()).increment();
		AlertLog saved = repository.save(new AlertLog(type, message, targetDate));
		notifySlack(type, message, targetDate);
		return saved;
	}

	private void notifySlack(AlertLog.Type type, String message, LocalDate targetDate) {
		if (!StringUtils.hasText(webhookUrl)) {
			return;
		}
		try {
			slack.post().uri(webhookUrl)
					.body(Map.of("text", ":warning: *[" + type + "]* " + targetDate + " " + message))
					.retrieve().toBodilessEntity();
		} catch (RuntimeException e) {
			log.warn("Slack 전송 실패: {}", e.getMessage());
		}
	}
}
