package kr.solarcast.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertLog {

	public enum Type {
		PERFORMANCE, RETRAIN_RECOMMENDED, DATA_MISSING, DATA_RANGE, FALLBACK_FORECAST,
		PIPELINE_FAILURE, OUTPUT_ANOMALY, MODEL_SWAP
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Enumerated(EnumType.STRING)
	private Type type;
	@Column(length = 1000)
	private String message;
	/** 대상 날짜 (리플레이 중이면 재생 중인 날짜) */
	private LocalDate targetDate;
	private LocalDateTime createdAt;
	private boolean resolved;

	public AlertLog(Type type, String message, LocalDate targetDate) {
		this.type = type;
		this.message = message;
		this.targetDate = targetDate;
		this.createdAt = LocalDateTime.now();
	}

	public void resolve() {
		this.resolved = true;
	}
}
