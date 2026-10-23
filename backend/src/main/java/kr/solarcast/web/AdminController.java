package kr.solarcast.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.solarcast.client.ModelApiClient;
import kr.solarcast.domain.ReplayState;
import kr.solarcast.domain.RunMode;
import kr.solarcast.domain.TargetType;
import kr.solarcast.service.EvaluationService;
import kr.solarcast.service.ForecastService;
import kr.solarcast.service.ModelSyncService;
import kr.solarcast.service.ReplayService;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 API", description = "HTTP Basic 인증 필요")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

	private final ForecastService forecastService;
	private final EvaluationService evaluationService;
	private final ReplayService replayService;
	private final ModelApiClient modelApi;
	private final ModelSyncService modelSync;

	@Operation(summary = "예측 수동 실행")
	@PostMapping("/forecasts/run")
	public Map<String, Object> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (TargetType t : TargetType.values()) {
			out.put(t.key(), forecastService.run(t, date, RunMode.MANUAL));
			evaluationService.evaluate(t, date).ifPresent(m -> out.put(t.key() + "_score", m.score()));
		}
		return out;
	}

	@Operation(summary = "리플레이 시작 (기본: 실측이 있는 마지막 90일)")
	@PostMapping("/replay/start")
	public ReplayState start(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "false") boolean auto) {
		return replayService.start(from, to, auto);
	}

	@Operation(summary = "리플레이 하루 진행")
	@PostMapping("/replay/step")
	public ReplayService.StepResult step() {
		return replayService.step();
	}

	@Operation(summary = "리플레이 자동 진행 켜기/끄기")
	@PostMapping("/replay/auto")
	public ReplayState auto(@RequestParam boolean on) {
		return replayService.setAuto(on);
	}

	@Operation(summary = "리플레이 중지")
	@PostMapping("/replay/stop")
	public ReplayState stop() {
		return replayService.stop();
	}

	@Operation(summary = "challenger 승격 (교체 규칙 통과 시에만). apply=false면 비교만")
	@PostMapping("/models/{target}/promote")
	public Map<String, Object> promote(@PathVariable String target, @RequestParam(defaultValue = "true") boolean apply) {
		var result = modelApi.promote(TargetType.from(target).key(), apply);
		modelSync.sync();
		return result;
	}

	@Operation(summary = "이전 champion으로 롤백")
	@PostMapping("/models/{target}/rollback")
	public Map<String, Object> rollback(@PathVariable String target) {
		var result = modelApi.rollback(TargetType.from(target).key());
		modelSync.sync();
		return result;
	}

	@Operation(summary = "재학습 시작 (결과는 challenger로 등록)")
	@PostMapping("/models/{target}/retrain")
	public Map<String, Object> retrain(@PathVariable String target) {
		return modelApi.retrain(TargetType.from(target).key());
	}

	@Operation(summary = "모델 레지스트리 동기화")
	@PostMapping("/models/sync")
	public Map<String, String> sync() {
		modelSync.sync();
		return Map.of("status", "ok");
	}
}
