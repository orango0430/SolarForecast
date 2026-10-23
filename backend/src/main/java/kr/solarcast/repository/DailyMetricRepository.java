package kr.solarcast.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import kr.solarcast.domain.DailyMetric;
import kr.solarcast.domain.TargetType;

public interface DailyMetricRepository extends JpaRepository<DailyMetric, Long> {

	Optional<DailyMetric> findByDateAndTargetType(LocalDate date, TargetType targetType);

	@Query("select m from DailyMetric m left join fetch m.modelVersion "
			+ "where m.targetType = :type and m.date between :from and :to order by m.date")
	List<DailyMetric> findRange(TargetType type, LocalDate from, LocalDate to);

	List<DailyMetric> findByTargetTypeAndDateLessThanEqualOrderByDateDesc(TargetType type, LocalDate date, Pageable page);
}
