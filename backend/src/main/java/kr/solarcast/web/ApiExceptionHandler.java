package kr.solarcast.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import kr.solarcast.service.ForecastService.ForecastFailedException;

@RestControllerAdvice(basePackageClasses = ApiController.class)
public class ApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler({ForecastFailedException.class, IllegalStateException.class})
	ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(RestClientResponseException.class)
	ResponseEntity<Map<String, String>> modelApi(RestClientResponseException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", "model-api " + e.getStatusCode().value(), "detail", e.getResponseBodyAsString()));
	}

	@ExceptionHandler(RestClientException.class)
	ResponseEntity<Map<String, String>> modelApiDown(RestClientException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("error", "model-api 연결 실패", "detail", String.valueOf(e.getMessage())));
	}
}
