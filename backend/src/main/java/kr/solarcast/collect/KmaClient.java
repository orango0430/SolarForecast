package kr.solarcast.collect;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import kr.solarcast.config.AppProperties;
import kr.solarcast.domain.WeatherForecast;
import kr.solarcast.domain.WeatherObservation;

/**
 * 기상청 공공데이터포털 API (단기예보 getVilageFcst, ASOS 시간자료 getWthrDataList).
 * 서비스 키가 있어야 동작한다. 키 확인은 POST /api/admin/kma/check.
 */
@Component
public class KmaClient {

	private static final String VILAGE = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";
	private static final String ASOS = "https://apis.data.go.kr/1360000/AsosHourlyInfoService/getWthrDataList";
	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HHmm");
	private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final RestClient rest;
	private final String serviceKey;

	public KmaClient(RestClient.Builder builder, AppProperties props) {
		this.rest = builder.build();
		this.serviceKey = encodeKey(props.kma().serviceKey());
	}

	/** 공공데이터포털은 Encoding/Decoding 키를 둘 다 준다. URI를 인코딩된 상태로 만들므로 Decoding 키는 인코딩한다. */
	static String encodeKey(String key) {
		if (!StringUtils.hasText(key)) {
			return key;
		}
		key = key.trim();
		return key.contains("%") ? key : URLEncoder.encode(key, StandardCharsets.UTF_8);
	}

	/** 단기예보 발표 시각(02, 05, ..., 23시) 중 지금 조회 가능한 가장 최근 것 (발표 후 약 10분 뒤 제공) */
	public static LocalDateTime latestBaseTime(LocalDateTime now) {
		LocalDateTime t = now.minusMinutes(15);
		int hour = t.getHour();
		int base = hour < 2 ? -1 : ((hour - 2) / 3) * 3 + 2;
		return base < 0 ? t.toLocalDate().minusDays(1).atTime(23, 0) : t.toLocalDate().atTime(base, 0);
	}

	public boolean enabled() {
		return StringUtils.hasText(serviceKey);
	}

	/** baseTime 발표 단기예보를 시각별 행으로 묶는다. 0000 예보는 hour-ending 기준 전날 24시. */
	public List<WeatherForecast> vilageForecast(int nx, int ny, LocalDateTime baseTime) {
		URI uri = UriComponentsBuilder.fromUriString(VILAGE)
				.queryParam("serviceKey", serviceKey)
				.queryParam("pageNo", 1).queryParam("numOfRows", 1000).queryParam("dataType", "JSON")
				.queryParam("base_date", baseTime.format(YMD)).queryParam("base_time", baseTime.format(HM))
				.queryParam("nx", nx).queryParam("ny", ny)
				.build(true).toUri();
		List<Map<String, Object>> items = items(uri);
		Map<LocalDateTime, Map<String, String>> byTime = new TreeMap<>();
		for (Map<String, Object> it : items) {
			LocalDateTime t = LocalDateTime.of(LocalDate.parse((String) it.get("fcstDate"), YMD),
					java.time.LocalTime.parse((String) it.get("fcstTime"), HM));
			byTime.computeIfAbsent(t, k -> new java.util.HashMap<>())
					.put((String) it.get("category"), String.valueOf(it.get("fcstValue")));
		}
		List<WeatherForecast> out = new ArrayList<>();
		byTime.forEach((t, v) -> out.add(WeatherForecast.builder()
				.nx(nx).ny(ny).baseTime(baseTime).fcstTime(t)
				.tmp(num(v.get("TMP"))).reh(num(v.get("REH")))
				.sky(intOrNull(v.get("SKY"))).pty(intOrNull(v.get("PTY")))
				.pop(num(v.get("POP"))).pcp(precip(v.get("PCP"))).wsd(num(v.get("WSD")))
				.build()));
		return out;
	}

	/** 전일 1~23시 ASOS 관측 (API는 D-1까지만 제공) */
	public List<WeatherObservation> asosHourly(int stnId, LocalDate date) {
		URI uri = UriComponentsBuilder.fromUriString(ASOS)
				.queryParam("serviceKey", serviceKey)
				.queryParam("pageNo", 1).queryParam("numOfRows", 24).queryParam("dataType", "JSON")
				.queryParam("dataCd", "ASOS").queryParam("dateCd", "HR")
				.queryParam("startDt", date.format(YMD)).queryParam("startHh", "01")
				.queryParam("endDt", date.format(YMD)).queryParam("endHh", "23")
				.queryParam("stnIds", stnId)
				.build(true).toUri();
		return items(uri).stream().map(it -> WeatherObservation.builder()
				.stnId(stnId)
				.obsTime(LocalDateTime.parse((String) it.get("tm"), TM))
				.ta(num(it.get("ta"))).hm(num(it.get("hm"))).rn(num(it.get("rn"))).ws(num(it.get("ws")))
				.dc10Tca(num(it.get("dc10Tca"))).ss(num(it.get("ss"))).icsr(num(it.get("icsr")))
				.build()).toList();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> items(URI uri) {
		Map<String, Object> body = rest.get().uri(uri).retrieve().body(new ParameterizedTypeReference<>() {
		});
		Map<String, Object> response = (Map<String, Object>) body.get("response");
		Map<String, Object> header = (Map<String, Object>) response.get("header");
		if (!"00".equals(header.get("resultCode"))) {
			throw new IllegalStateException("KMA " + header.get("resultCode") + " " + header.get("resultMsg"));
		}
		Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) response.get("body")).get("items");
		return (List<Map<String, Object>>) items.get("item");
	}

	static Double num(Object o) {
		if (o == null || !StringUtils.hasText(o.toString())) {
			return null;
		}
		try {
			return Double.parseDouble(o.toString());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	static Integer intOrNull(String s) {
		Double d = num(s);
		return d == null ? null : d.intValue();
	}

	/** PCP: "강수없음", "1mm 미만", "1.0mm", "30.0~50.0mm", "50.0mm 이상" */
	static Double precip(String s) {
		if (s == null || s.contains("없음")) {
			return 0.0;
		}
		if (s.contains("미만")) {
			return 0.5;
		}
		String n = s.replaceAll("[^0-9.~]", "");
		if (n.contains("~")) {
			n = n.substring(0, n.indexOf('~'));
		}
		return num(n);
	}
}
