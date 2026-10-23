package kr.solarcast.client;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import kr.solarcast.client.ModelApiDtos.PredictRequest;
import kr.solarcast.client.ModelApiDtos.PredictResponse;
import kr.solarcast.client.ModelApiDtos.RegistryModel;
import kr.solarcast.config.AppProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 내부망 FastAPI 호출. 타임아웃을 걸고, 4xx(요청 오류)를 뺀 실패는 재시도한다.
 */
@Slf4j
@Component
public class ModelApiClient {

	private final RestClient rest;
	private final int retries;

	public ModelApiClient(RestClient.Builder builder, AppProperties props) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(props.modelApi().connectTimeout());
		factory.setReadTimeout(props.modelApi().readTimeout());
		this.rest = builder.baseUrl(props.modelApi().url()).requestFactory(factory).build();
		this.retries = Math.max(1, props.modelApi().retries());
	}

	public PredictResponse predict(PredictRequest req) {
		return withRetry("predict", () -> rest.post().uri("/predict")
				.contentType(MediaType.APPLICATION_JSON)
				.body(req)
				.retrieve()
				.body(PredictResponse.class));
	}

	public Map<String, Object> health() {
		return rest.get().uri("/health").retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	public Map<String, List<RegistryModel>> models() {
		return withRetry("models", () -> rest.get().uri("/models").retrieve()
				.body(new ParameterizedTypeReference<Map<String, List<RegistryModel>>>() {
				}));
	}

	public Map<String, Object> promote(String target, boolean apply) {
		return rest.post().uri("/models/{t}/promote?apply={a}", target, apply).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public Map<String, Object> rollback(String target) {
		return rest.post().uri("/models/{t}/rollback", target).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public Map<String, Object> retrain(String target) {
		return rest.post().uri("/models/{t}/retrain", target).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	private <T> T withRetry(String op, Supplier<T> call) {
		RuntimeException last = null;
		for (int attempt = 1; attempt <= retries; attempt++) {
			try {
				return call.get();
			} catch (HttpClientErrorException e) {
				throw e; // 입력 오류는 다시 보내도 같다
			} catch (RuntimeException e) {
				last = e;
				log.warn("model-api {} 실패 ({}/{}): {}", op, attempt, retries, e.getMessage());
				sleep(300L * attempt);
			}
		}
		throw last;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
