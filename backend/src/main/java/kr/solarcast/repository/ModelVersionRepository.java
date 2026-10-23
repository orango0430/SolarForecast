package kr.solarcast.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.ModelVersion;
import kr.solarcast.domain.TargetType;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {

	Optional<ModelVersion> findByTargetTypeAndRegistryVersion(TargetType targetType, String registryVersion);

	Optional<ModelVersion> findFirstByTargetTypeAndStage(TargetType targetType, String stage);

	List<ModelVersion> findByTargetTypeOrderByIdDesc(TargetType targetType);
}
