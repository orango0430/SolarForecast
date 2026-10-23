package kr.solarcast.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * issuedAt(예측 기준 시각)을 남겨 같은 시각에 대한 예측이 여러 번 생겨도
 * 그때 무엇을 알고 예측했는지 재현할 수 있게 한다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(columnList = "targetType, targetTime"))
public class Forecast {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Enumerated(EnumType.STRING)
	private TargetType targetType;
	private LocalDateTime targetTime;
	private LocalDateTime issuedAt;
	private double p10;
	private double p50;
	private double p90;
	@ManyToOne(fetch = FetchType.LAZY)
	private ModelVersion modelVersion;
	/** 17시 예보 수집 실패로 14시 발표분을 쓴 경우 */
	private boolean fallback;
	@Enumerated(EnumType.STRING)
	private RunMode mode;
	private LocalDateTime createdAt;
}
