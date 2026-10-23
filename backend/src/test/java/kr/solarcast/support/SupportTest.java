package kr.solarcast.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class SupportTest {

	@Test
	void gridMatchesKmaExample() {
		// Python solarcast/grid.py 테스트와 같은 지점: 서울 종로구 (60, 127)
		assertThat(Grids.toGrid(37.5714, 126.9658)).isEqualTo(new Grids.Grid(60, 127));
	}

	@Test
	void hourEndingRules() {
		LocalDate d = LocalDate.of(2025, 6, 2);
		assertThat(Times.issuedAt(d)).isEqualTo(LocalDateTime.of(2025, 6, 1, 17, 0));
		assertThat(Times.lastHour(d)).isEqualTo(LocalDateTime.of(2025, 6, 3, 0, 0));
		assertThat(Times.dateOf(LocalDateTime.of(2025, 6, 3, 0, 0))).isEqualTo(d);
	}
}
