package kr.solarcast.collect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KmaClientTest {

	@Test
	void parsesPrecipitationText() {
		assertThat(KmaClient.precip("강수없음")).isZero();
		assertThat(KmaClient.precip("1mm 미만")).isEqualTo(0.5);
		assertThat(KmaClient.precip("3.0mm")).isEqualTo(3.0);
		assertThat(KmaClient.precip("30.0~50.0mm")).isEqualTo(30.0);
		assertThat(KmaClient.precip("50.0mm 이상")).isEqualTo(50.0);
	}
}
