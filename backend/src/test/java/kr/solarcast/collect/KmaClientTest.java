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

	@Test
	void encodesDecodingKeyOnly() {
		assertThat(KmaClient.encodeKey("ab+c/d==")).isEqualTo("ab%2Bc%2Fd%3D%3D");
		assertThat(KmaClient.encodeKey("ab%2Bc%2Fd%3D%3D")).isEqualTo("ab%2Bc%2Fd%3D%3D");
	}

	@Test
	void detectsProviderFromKeyShape() {
		assertThat(KmaClient.extractKey("https://apihub.kma.go.kr/api/typ01/url/x.php?tmfc=0&authKey=abcDEF_12-xyz"))
				.isEqualTo("abcDEF_12-xyz");
		assertThat(KmaClient.providerOf("AbCdEfGhIj-KlMnOp_QrSt")).isEqualTo(KmaClient.Provider.APIHUB);
		assertThat(KmaClient.providerOf("a".repeat(60) + "+/==")).isEqualTo(KmaClient.Provider.DATA_GO_KR);
	}

	@Test
	void latestBaseTimeFollowsReleaseSchedule() {
		var d = java.time.LocalDate.of(2026, 10, 24);
		assertThat(KmaClient.latestBaseTime(d.atTime(17, 20))).isEqualTo(d.atTime(17, 0));
		assertThat(KmaClient.latestBaseTime(d.atTime(17, 10))).isEqualTo(d.atTime(14, 0));
		assertThat(KmaClient.latestBaseTime(d.atTime(1, 0))).isEqualTo(d.minusDays(1).atTime(23, 0));
	}
}
