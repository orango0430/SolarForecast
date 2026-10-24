package kr.solarcast.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class KpxSukubClientTest {

	@Test
	void averagesFiveMinuteLoadIntoHourEnding() {
		StringBuilder csv = new StringBuilder("기준일시,공급능력(MW),현재수요(MW),최대예측수요(MW)\n");
		// 00:00 샘플은 전날 24시 구간, 00:05~01:00은 1시 구간
		csv.append("20261020000000,90000,50000,71800\n");
		for (int m = 5; m <= 60; m += 5) {
			String hhmm = m == 60 ? "0100" : String.format("00%02d", m);
			csv.append("20261020").append(hhmm).append("00,90000,").append(m <= 30 ? 60000 : 62000).append(",71800\n");
		}
		csv.append("20261020010500,90000,0,71800\n"); // 0은 결측으로 본다

		var rows = KpxSukubClient.aggregate(csv.toString());
		assertThat(rows).hasSize(1); // 00:00(샘플 1개)과 02:00(0개)은 개수 부족으로 버림
		var h1 = rows.get(0);
		assertThat(h1.targetTime()).isEqualTo(LocalDateTime.of(2026, 10, 20, 1, 0));
		assertThat(h1.samples()).isEqualTo(12);
		assertThat(h1.demandMwh()).isEqualTo(61000.0);
	}
}
