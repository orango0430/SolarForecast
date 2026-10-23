package kr.solarcast.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.solarcast.client.ModelApiClient;
import kr.solarcast.client.ModelApiDtos.RegistryModel;
import kr.solarcast.domain.AlertLog;
import kr.solarcast.domain.ModelVersion;
import kr.solarcast.domain.TargetType;
import kr.solarcast.repository.ModelVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** MLflow 레지스트리(모델 서버 경유)의 버전·alias를 model_version 테이블에 맞춘다. 교체 이력도 여기서 남는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelSyncService {

	private final ModelApiClient modelApi;
	private final ModelVersionRepository repository;
	private final AlertService alerts;

	@Scheduled(initialDelay = 10_000, fixedDelay = 60_000)
	public void scheduledSync() {
		try {
			sync();
		} catch (RuntimeException e) {
			log.debug("모델 동기화 건너뜀: {}", e.getMessage());
		}
	}

	@Transactional
	public void sync() {
		Map<String, List<RegistryModel>> all = modelApi.models();
		for (TargetType target : TargetType.values()) {
			for (RegistryModel m : all.getOrDefault(target.key(), List.of())) {
				ModelVersion mv = repository.findByTargetTypeAndRegistryVersion(target, m.version())
						.orElseGet(() -> repository.save(new ModelVersion(target, m.version())));
				String stage = m.aliases() == null || m.aliases().isEmpty() ? null : stageOf(m.aliases());
				Double validation = m.metrics() == null ? null
						: m.metrics().getOrDefault(target == TargetType.SOLAR ? "cv_nmae" : "cv_mape", null);
				boolean promoted = mv.sync(m.runId(), stage, metricsText(m.metrics()), validation);
				// 처음 등록된 단 하나의 버전이 champion이 되는 건 교체가 아니다
				if (promoted && repository.findByTargetTypeOrderByIdDesc(target).size() > 1) {
					alerts.raise(AlertLog.Type.MODEL_SWAP, target.key() + " champion -> v" + m.version(), null);
				}
			}
		}
	}

	/** 예측 응답의 버전 문자열을 model_version 행으로. 아직 동기화 전이면 먼저 동기화한다. */
	@Transactional
	public ModelVersion resolve(TargetType target, String registryVersion) {
		return repository.findByTargetTypeAndRegistryVersion(target, registryVersion).orElseGet(() -> {
			try {
				sync();
			} catch (RuntimeException e) {
				log.warn("모델 동기화 실패: {}", e.getMessage());
			}
			return repository.findByTargetTypeAndRegistryVersion(target, registryVersion)
					.orElseGet(() -> repository.save(new ModelVersion(target, registryVersion)));
		});
	}

	private static String stageOf(List<String> aliases) {
		for (String s : List.of("champion", "challenger", "previous")) {
			if (aliases.contains(s)) {
				return s;
			}
		}
		return aliases.get(0);
	}

	private static String metricsText(Map<String, Double> metrics) {
		if (metrics == null) {
			return null;
		}
		return metrics.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(e -> e.getKey() + "=" + (e.getValue() == null ? "-" : String.format("%.3f", e.getValue())))
				.collect(Collectors.joining(", "));
	}
}
