package kr.solarcast.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import kr.solarcast.domain.SolarActual;

public interface SolarActualRepository extends JpaRepository<SolarActual, Long> {

	List<SolarActual> findByPlantIdAndTargetTimeBetweenOrderByTargetTime(Long plantId, LocalDateTime from, LocalDateTime to);

	@Query("select max(s.targetTime) from SolarActual s")
	LocalDateTime findMaxTargetTime();
}
