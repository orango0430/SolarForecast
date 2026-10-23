package kr.solarcast.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
		ModelApi modelApi,
		Admin admin,
		Seed seed,
		Kma kma,
		Slack slack,
		Replay replay,
		Live live,
		Monitor monitor) {

	public record ModelApi(String url, Duration connectTimeout, Duration readTimeout, int retries) {
	}

	public record Admin(String username, String password) {
	}

	public record Seed(String dir, boolean enabled) {
	}

	public record Kma(String serviceKey) {
	}

	public record Slack(String webhookUrl) {
	}

	public record Replay(long autoIntervalMs) {
	}

	public record Live(boolean enabled) {
	}

	public record Monitor(double ratioThreshold, int consecutiveDays, int windowDays) {
	}
}
