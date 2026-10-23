package kr.solarcast.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.WeatherObservation;

public interface WeatherObservationRepository extends JpaRepository<WeatherObservation, Long> {

	boolean existsByStnIdAndObsTime(int stnId, LocalDateTime obsTime);
}
