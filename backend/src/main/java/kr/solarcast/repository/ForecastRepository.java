package kr.solarcast.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import kr.solarcast.domain.Forecast;
import kr.solarcast.domain.TargetType;

public interface ForecastRepository extends JpaRepository<Forecast, Long> {

	@Query("select f from Forecast f left join fetch f.modelVersion "
			+ "where f.targetType = :type and f.targetTime between :from and :to "
			+ "order by f.targetTime, f.issuedAt desc, f.createdAt desc")
	List<Forecast> findForRange(TargetType type, LocalDateTime from, LocalDateTime to);

	@Modifying
	@Query("delete from Forecast f where f.targetType = :type and f.issuedAt = :issuedAt "
			+ "and f.targetTime between :from and :to")
	int deleteIssued(TargetType type, LocalDateTime issuedAt, LocalDateTime from, LocalDateTime to);
}
