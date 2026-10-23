package kr.solarcast.domain;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"date", "targetType"}))
public class DailyMetric {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private LocalDate date;
	@Enumerated(EnumType.STRING)
	private TargetType targetType;
	@ManyToOne(fetch = FetchType.LAZY)
	private ModelVersion modelVersion;
	/** 태양광: 이용률 10% 이상 시간 기준 설비용량 대비 오차율 */
	private Double nmae;
	/** 수요: 평균 절대 백분율 오차 */
	private Double mape;
	/** 맑음 예보인데 실측이 예측 하한(P10)보다 낮은 시간 수 (출력 이상 탐지) */
	private int belowP10Hours;

	public DailyMetric(LocalDate date, TargetType targetType) {
		this.date = date;
		this.targetType = targetType;
	}

	public void update(ModelVersion modelVersion, Double nmae, Double mape, int belowP10Hours) {
		this.modelVersion = modelVersion;
		this.nmae = nmae;
		this.mape = mape;
		this.belowP10Hours = belowP10Hours;
	}

	public Double score() {
		return targetType == TargetType.SOLAR ? nmae : mape;
	}
}
