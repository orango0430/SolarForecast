package kr.solarcast.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** MLflow 레지스트리 버전의 사본. stage는 alias(champion, challenger, previous) 중 하나 또는 null. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"targetType", "registryVersion"}))
public class ModelVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Enumerated(EnumType.STRING)
	private TargetType targetType;
	private String registryVersion;
	private String runId;
	private String stage;
	@Column(length = 4000)
	private String metrics;
	/** 검증(CV) 성능. 감시 기준(1.5배)의 분모 */
	private Double validationScore;
	private LocalDateTime createdAt;
	private LocalDateTime promotedAt;

	public ModelVersion(TargetType targetType, String registryVersion) {
		this.targetType = targetType;
		this.registryVersion = registryVersion;
		this.createdAt = LocalDateTime.now();
	}

	/** @return champion으로 새로 바뀌었으면 true */
	public boolean sync(String runId, String stage, String metrics, Double validationScore) {
		boolean promoted = "champion".equals(stage) && !"champion".equals(this.stage);
		if (promoted) {
			this.promotedAt = LocalDateTime.now();
		}
		this.runId = runId;
		this.stage = stage;
		this.metrics = metrics;
		this.validationScore = validationScore;
		return promoted;
	}
}
