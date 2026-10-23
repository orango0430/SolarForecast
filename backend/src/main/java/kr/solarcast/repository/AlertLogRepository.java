package kr.solarcast.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.AlertLog;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

	List<AlertLog> findTop100ByOrderByIdDesc();

	List<AlertLog> findTop50ByTypeOrderByIdDesc(AlertLog.Type type);

	boolean existsByTypeAndTargetDateBetween(AlertLog.Type type, LocalDate from, LocalDate to);
}
