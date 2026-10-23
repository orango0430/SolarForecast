package kr.solarcast.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import kr.solarcast.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비어 있는 DB에 ml이 만든 data/processed/*.csv를 넣는다.
 * 리플레이 모드의 '과거 예보 아카이브'와 실측이 여기서 들어온다. 수십만 행이라 JDBC 배치로 넣는다.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd[ HH:mm:ss][ HH:mm]");
	private static final int BATCH = 2000;

	private final JdbcTemplate jdbc;
	private final AppProperties props;

	/** 테이블 -> (CSV 컬럼, 타입) . 타입: T=시각, D=실수, I=정수, S=문자 */
	private static final Map<String, String[][]> TABLES = new java.util.LinkedHashMap<>();

	static {
		TABLES.put("plant", new String[][] {{"id", "I"}, {"name", "S"}, {"capacity_kw", "D"}, {"lat", "D"},
				{"lon", "D"}, {"asos_stn_id", "I"}, {"nx", "I"}, {"ny", "I"}});
		TABLES.put("solar_actual", new String[][] {{"plant_id", "I"}, {"target_time", "T"}, {"generation_kwh", "D"}});
		TABLES.put("demand_actual", new String[][] {{"target_time", "T"}, {"demand_mwh", "D"}});
		TABLES.put("weather_forecast", new String[][] {{"nx", "I"}, {"ny", "I"}, {"base_time", "T"},
				{"fcst_time", "T"}, {"tmp", "D"}, {"reh", "D"}, {"sky", "I"}, {"pty", "I"}, {"pop", "D"},
				{"pcp", "D"}, {"wsd", "D"}});
		TABLES.put("weather_observation", new String[][] {{"stn_id", "I"}, {"obs_time", "T"}, {"ta", "D"},
				{"hm", "D"}, {"rn", "D"}, {"ws", "D"}, {"dc10_tca", "D"}, {"ss", "D"}, {"icsr", "D"}});
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		if (!props.seed().enabled()) {
			return;
		}
		Path dir = Path.of(props.seed().dir());
		if (!Files.isDirectory(dir)) {
			log.warn("시드 디렉터리 없음: {} (ml 쪽에서 python -m solarcast.real all 먼저 실행)", dir.toAbsolutePath());
			return;
		}
		for (var e : TABLES.entrySet()) {
			Integer count = jdbc.queryForObject("select count(*) from " + e.getKey(), Integer.class);
			Path csv = dir.resolve(e.getKey() + ".csv");
			if (count != null && count > 0 || !Files.exists(csv)) {
				continue;
			}
			long t0 = System.currentTimeMillis();
			int n = load(e.getKey(), e.getValue(), csv);
			log.info("seed {}: {} rows ({} ms)", e.getKey(), n, System.currentTimeMillis() - t0);
		}
	}

	int load(String table, String[][] cols, Path csv) throws IOException {
		StringBuilder sql = new StringBuilder("insert into ").append(table).append(" (");
		for (int i = 0; i < cols.length; i++) {
			sql.append(i > 0 ? "," : "").append(cols[i][0]);
		}
		sql.append(") values (").append("?,".repeat(cols.length - 1)).append("?)");

		int total = 0;
		try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
			String[] header = r.readLine().replace("﻿", "").split(",");
			Map<String, Integer> idx = new HashMap<>();
			for (int i = 0; i < header.length; i++) {
				idx.put(header[i].trim(), i);
			}
			List<Object[]> batch = new ArrayList<>(BATCH);
			int[] types = new int[cols.length];
			for (int i = 0; i < cols.length; i++) {
				types[i] = switch (cols[i][1]) {
					case "T" -> Types.TIMESTAMP;
					case "D" -> Types.DOUBLE;
					case "I" -> Types.INTEGER;
					default -> Types.VARCHAR;
				};
			}
			String line;
			while ((line = r.readLine()) != null) {
				String[] v = line.split(",", -1);
				Object[] row = new Object[cols.length];
				for (int i = 0; i < cols.length; i++) {
					Integer j = idx.get(cols[i][0]);
					row[i] = j == null || j >= v.length ? null : parse(v[j], cols[i][1]);
				}
				batch.add(row);
				if (batch.size() == BATCH) {
					jdbc.batchUpdate(sql.toString(), batch, types);
					total += batch.size();
					batch.clear();
				}
			}
			if (!batch.isEmpty()) {
				jdbc.batchUpdate(sql.toString(), batch, types);
				total += batch.size();
			}
		}
		return total;
	}

	static Object parse(String s, String type) {
		s = s.trim();
		if (s.isEmpty() || s.equalsIgnoreCase("nan")) {
			return null;
		}
		return switch (type) {
			case "T" -> Timestamp.valueOf(LocalDateTime.parse(s.replace('T', ' '), TS));
			case "D" -> Double.parseDouble(s);
			case "I" -> (int) Double.parseDouble(s);
			default -> s;
		};
	}
}
