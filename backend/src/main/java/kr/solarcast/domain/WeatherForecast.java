package kr.solarcast.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 단기예보. 같은 (격자, 예보시각)이라도 발표시각(baseTime)별로 따로 저장한다. */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"nx", "ny", "baseTime", "fcstTime"}),
		indexes = @Index(columnList = "baseTime, nx, ny"))
public class WeatherForecast {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private int nx;
	private int ny;
	private LocalDateTime baseTime;
	private LocalDateTime fcstTime;
	private Double tmp;
	private Double reh;
	private Integer sky;
	private Integer pty;
	private Double pop;
	private Double pcp;
	private Double wsd;
}
