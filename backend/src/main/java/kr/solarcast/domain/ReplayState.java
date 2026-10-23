package kr.solarcast.domain;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 리플레이 진행 상태. 한 행만 쓴다. */
@Entity
@Getter
@NoArgsConstructor
public class ReplayState {

	public static final long SINGLETON_ID = 1L;

	@Id
	private Long id = SINGLETON_ID;
	private LocalDate startDate;
	private LocalDate endDate;
	/** 다음에 재생할 날짜 */
	private LocalDate cursor;
	private boolean running;
	private boolean auto;

	public void start(LocalDate start, LocalDate end, boolean auto) {
		this.startDate = start;
		this.endDate = end;
		this.cursor = start;
		this.running = true;
		this.auto = auto;
	}

	public void advance() {
		cursor = cursor.plusDays(1);
		if (finished()) {
			running = false;
		}
	}

	public void setAuto(boolean auto) {
		this.auto = auto;
		if (auto && !finished()) {
			running = true;
		}
	}

	public void stop() {
		running = false;
		auto = false;
	}

	public boolean finished() {
		return cursor == null || cursor.isAfter(endDate);
	}
}
