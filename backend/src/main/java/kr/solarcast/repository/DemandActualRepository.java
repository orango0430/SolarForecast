package kr.solarcast.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import kr.solarcast.domain.DemandActual;

public interface DemandActualRepository extends JpaRepository<DemandActual, Long> {

	List<DemandActual> findByTargetTimeBetweenOrderByTargetTime(LocalDateTime from, LocalDateTime to);

	@Query("select max(d.targetTime) from DemandActual d")
	LocalDateTime findMaxTargetTime();
}
