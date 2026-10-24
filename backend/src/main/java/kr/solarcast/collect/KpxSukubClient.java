package kr.solarcast.collect;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 전력거래소 전력수급현황 (openapi.kpx.or.kr/sukub.do) 5분 단위 CSV. 키 없이 최근 1년까지 받을 수 있다.
 *
 * 5분 현재수요(MW)의 시간 평균은 공공데이터포털 '시간별 전국 전력수요량'(발전단 MWh)과
 * 2025-10~12 겹치는 1,633시간에서 평균 차이 0.16%로 같아 보정 없이 수요 실측으로 쓴다.
 */
@Component
public class KpxSukubClient {

	private static final String BASE = "https://openapi.kpx.or.kr";
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
	/** 한 시간 12개 중 이 개수 이상 있어야 시간값으로 인정 */
	static final int MIN_SAMPLES = 10;

	private final RestClient rest;

	public KpxSukubClient(RestClient.Builder builder) {
		this.rest = builder.baseUrl(BASE).defaultHeader("User-Agent", "Mozilla/5.0 solarcast").build();
	}

	public record HourlyDemand(LocalDateTime targetTime, double demandMwh, int samples) {
	}

	/** from ~ to (포함) 기간의 시간별 수요 (hour-ending) */
	public List<HourlyDemand> hourly(LocalDate from, LocalDate to) {
		var form = new LinkedMultiValueMap<String, String>();
		form.add("startDate", from.toString());
		form.add("endDate", to.toString());
		byte[] body = rest.post().uri("/downloadSukubCSV.do")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(byte[].class);
		return aggregate(new String(body == null ? new byte[0] : body, Charset.forName("EUC-KR")));
	}

	/** 5분 값을 (h-1, h] 구간 평균으로 묶는다. 13:00 값 = 12:05 ~ 13:00 */
	static List<HourlyDemand> aggregate(String csv) {
		Map<LocalDateTime, double[]> acc = new TreeMap<>(); // [합, 개수]
		for (String line : csv.split("\\r?\\n")) {
			String[] v = line.split(",");
			if (v.length < 3 || !v[0].matches("\\d{14}")) {
				continue; // 헤더나 빈 줄
			}
			double load;
			try {
				load = Double.parseDouble(v[2].trim());
			} catch (NumberFormatException e) {
				continue;
			}
			if (load <= 0) {
				continue;
			}
			LocalDateTime t = LocalDateTime.parse(v[0], TS);
			LocalDateTime hourEnding = t.getMinute() == 0 && t.getSecond() == 0
					? t : t.withMinute(0).withSecond(0).plusHours(1);
			double[] a = acc.computeIfAbsent(hourEnding, k -> new double[2]);
			a[0] += load;
			a[1]++;
		}
		List<HourlyDemand> out = new ArrayList<>();
		acc.forEach((t, a) -> {
			if (a[1] >= MIN_SAMPLES) {
				out.add(new HourlyDemand(t, Math.round(a[0] / a[1] * 10) / 10.0, (int) a[1]));
			}
		});
		return out;
	}
}
