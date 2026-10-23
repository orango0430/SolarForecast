package kr.solarcast.client;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** FastAPI 모델 서버와 주고받는 메시지. 필드명은 Python 쪽 snake_case를 따른다. */
public final class ModelApiDtos {

	private ModelApiDtos() {
	}

	public record WeatherRow(
			@JsonProperty("fcst_time") LocalDateTime fcstTime,
			Double tmp, Double reh, Integer sky, Integer pty, Double wsd) {
	}

	public record HistoryRow(LocalDateTime time, double value) {
	}

	public record PredictRequest(
			String target,
			@JsonProperty("target_date") LocalDate targetDate,
			List<WeatherRow> weather,
			List<HistoryRow> history,
			@JsonProperty("issued_at") LocalDateTime issuedAt) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record HourPrediction(
			@JsonProperty("target_time") LocalDateTime targetTime,
			double p10, double p50, double p90) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PredictResponse(
			String target,
			@JsonProperty("model_version") String modelVersion,
			@JsonProperty("model_source") String modelSource,
			@JsonProperty("issued_at") LocalDateTime issuedAt,
			List<HourPrediction> predictions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RegistryModel(
			String version,
			List<String> aliases,
			@JsonProperty("run_id") String runId,
			Map<String, Double> metrics,
			Map<String, String> params) {
	}
}
