package kr.solarcast.domain;

public enum TargetType {
	SOLAR, DEMAND;

	public static TargetType from(String s) {
		return valueOf(s.toUpperCase());
	}

	public String key() {
		return name().toLowerCase();
	}
}
