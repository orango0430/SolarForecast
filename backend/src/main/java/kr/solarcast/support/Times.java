package kr.solarcast.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 시간 규칙: KST, hour-ending. D일의 1~24시 = D 01:00 ~ D+1 00:00. */
public final class Times {

	public static final LocalTime ISSUE_TIME = LocalTime.of(17, 0);
	public static final LocalTime FALLBACK_ISSUE_TIME = LocalTime.of(14, 0);

	private Times() {
	}

	public static LocalDateTime firstHour(LocalDate d) {
		return d.atTime(1, 0);
	}

	public static LocalDateTime lastHour(LocalDate d) {
		return d.plusDays(1).atStartOfDay();
	}

	/** D일 예측의 기준 시각 = D-1일 17시 발표 */
	public static LocalDateTime issuedAt(LocalDate target) {
		return target.minusDays(1).atTime(ISSUE_TIME);
	}

	public static LocalDateTime fallbackIssuedAt(LocalDate target) {
		return target.minusDays(1).atTime(FALLBACK_ISSUE_TIME);
	}

	/** hour-ending 시각이 속한 날짜 (24시 = 다음날 00:00은 전날) */
	public static LocalDate dateOf(LocalDateTime hourEnding) {
		return hourEnding.minusHours(1).toLocalDate();
	}
}
