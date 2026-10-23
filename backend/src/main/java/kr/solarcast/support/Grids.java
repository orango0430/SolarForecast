package kr.solarcast.support;

import java.util.List;

/** 위경도 -> 기상청 단기예보 격자 (Python solarcast/grid.py와 같은 공식). */
public final class Grids {

	public record Grid(int nx, int ny) {
	}

	/** 수요용 5개 도시 (서울, 부산, 대구, 광주, 대전) */
	public record City(int stnId, String name, double lat, double lon) {
		public Grid grid() {
			return toGrid(lat, lon);
		}
	}

	public static final List<City> DEMAND_CITIES = List.of(
			new City(108, "서울", 37.57, 126.97),
			new City(159, "부산", 35.10, 129.03),
			new City(143, "대구", 35.88, 128.62),
			new City(156, "광주", 35.17, 126.89),
			new City(133, "대전", 36.37, 127.37));

	private Grids() {
	}

	public static Grid toGrid(double lat, double lon) {
		double re = 6371.00877 / 5.0;
		double d = Math.PI / 180.0;
		double slat1 = 30.0 * d, slat2 = 60.0 * d, olon = 126.0 * d, olat = 38.0 * d;
		double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
		sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
		double sf = Math.pow(Math.tan(Math.PI * 0.25 + slat1 * 0.5), sn) * Math.cos(slat1) / sn;
		double ro = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + olat * 0.5), sn);
		double ra = re * sf / Math.pow(Math.tan(Math.PI * 0.25 + lat * d * 0.5), sn);
		double theta = lon * d - olon;
		if (theta > Math.PI) {
			theta -= 2.0 * Math.PI;
		}
		if (theta < -Math.PI) {
			theta += 2.0 * Math.PI;
		}
		theta *= sn;
		int nx = (int) Math.floor(ra * Math.sin(theta) + 43 + 0.5);
		int ny = (int) Math.floor(ro - ra * Math.cos(theta) + 136 + 0.5);
		return new Grid(nx, ny);
	}
}
