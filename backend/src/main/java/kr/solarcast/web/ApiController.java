package kr.solarcast.web;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.DailyMetric;
import kr.solarcast.domain.Forecast;
import kr.solarcast.domain.ModelVersion;
import kr.solarcast.domain.TargetType;
import kr.solarcast.repository.AlertLogRepository;
import kr.solarcast.repository.DailyMetricRepository;
import kr.solarcast.repository.ModelVersionRepository;
import kr.solarcast.service.EvaluationService;
import kr.solarcast.service.ForecastService;
import kr.solarcast.service.ReplayService;
import kr.solarcast.support.Times;
import lombok.RequiredArgsConstructor;

@Tag(name = "조회 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {

	private final EvaluationService evaluationService;
	private final ForecastService forecastService;
	private final DailyMetricRepository metricRepository;
	private final ModelVersionRepository modelRepository;
	private final AlertLogRepository alertRepository;
	private final ReplayService replayService;

	public record ForecastDto(LocalDateTime targetTime, double p10, double p50, double p90, LocalDateTime issuedAt,
			String modelVersion, boolean fallback, String mode) {
		static ForecastDto of(Forecast f) {
			return new ForecastDto(f.getTargetTime(), f.getP10(), f.getP50(), f.getP90(), f.getIssuedAt(),
					f.getModelVersion() == null ? null : f.getModelVersion().getRegistryVersion(), f.isFallback(),
					f.getMode().name());
		}
	}

	public record ActualDto(LocalDateTime targetTime, double value) {
	}

	public record MetricDto(LocalDate date, Double nmae, Double mape, int belowP10Hours, String modelVersion) {
		static MetricDto of(DailyMetric m) {
			return new MetricDto(m.getDate(), m.getNmae(), m.getMape(), m.getBelowP10Hours(),
					m.getModelVersion() == null ? null : m.getModelVersion().getRegistryVersion());
		}
	}

	public record ModelDto(String target, String version, String stage, Double validationScore, String metrics,
			String runId, LocalDateTime createdAt, LocalDateTime promotedAt) {
		static ModelDto of(ModelVersion m) {
			return new ModelDto(m.getTargetType().key(), m.getRegistryVersion(), m.getStage(), m.getValidationScore(),
					m.getMetrics(), m.getRunId(), m.getCreatedAt(), m.getPromotedAt());
		}
	}

	public record WeatherDto(LocalDateTime time, Double tmp, Double reh, Integer sky, Integer pty, Double wsd) {
	}

	@Operation(summary = "시간별 예측 24개 (P10·P50·P90)")
	@GetMapping("/forecasts")
	public List<ForecastDto> forecasts(@RequestParam String target,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return evaluationService.latestForecasts(TargetType.from(target), date).values().stream()
				.map(ForecastDto::of).toList();
	}

	@Operation(summary = "시간별 실측")
	@GetMapping("/actuals")
	public List<ActualDto> actuals(@RequestParam String target,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return evaluationService.actuals(TargetType.from(target), date).entrySet().stream()
				.map(e -> new ActualDto(e.getKey(), e.getValue())).toList();
	}

	@Operation(summary = "예측에 쓴 기상 (태양광 발전소 격자, 17시 발표)")
	@GetMapping("/weather")
	public List<WeatherDto> weather(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		var rows = forecastService.plantWeather(date, Times.issuedAt(date));
		if (rows.isEmpty()) {
			rows = forecastService.plantWeather(date, Times.fallbackIssuedAt(date));
		}
		return rows.stream().map(w -> new WeatherDto(w.getFcstTime(), w.getTmp(), w.getReh(), w.getSky(), w.getPty(),
				w.getWsd())).toList();
	}

	@Operation(summary = "일별 오차 지표")
	@GetMapping("/metrics/daily")
	public List<MetricDto> metrics(@RequestParam String target,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return metricRepository.findRange(TargetType.from(target),
				from != null ? from : LocalDate.of(2000, 1, 1), to != null ? to : LocalDate.of(2100, 1, 1))
				.stream().map(MetricDto::of).toList();
	}

	@Operation(summary = "모델 버전 목록과 서빙 중인 버전")
	@GetMapping("/models")
	public Map<String, List<ModelDto>> models() {
		return Map.of(
				"solar", modelRepository.findByTargetTypeOrderByIdDesc(TargetType.SOLAR).stream().map(ModelDto::of).toList(),
				"demand", modelRepository.findByTargetTypeOrderByIdDesc(TargetType.DEMAND).stream().map(ModelDto::of).toList());
	}

	@Operation(summary = "최근 경고")
	@GetMapping("/alerts")
	public List<AlertLog> alerts() {
		return alertRepository.findTop100ByOrderByIdDesc();
	}

	@Operation(summary = "리플레이 상태")
	@GetMapping("/replay/status")
	public Map<String, Object> replayStatus() {
		return replayService.status();
	}
}
