package kr.solarcast.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.WeatherForecast;

public interface WeatherForecastRepository extends JpaRepository<WeatherForecast, Long> {

	List<WeatherForecast> findByNxAndNyAndBaseTimeAndFcstTimeBetweenOrderByFcstTime(
			int nx, int ny, LocalDateTime baseTime, LocalDateTime from, LocalDateTime to);

	boolean existsByNxAndNyAndBaseTimeAndFcstTime(int nx, int ny, LocalDateTime baseTime, LocalDateTime fcstTime);
}
