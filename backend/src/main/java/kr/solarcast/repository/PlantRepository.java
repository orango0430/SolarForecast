package kr.solarcast.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.Plant;

public interface PlantRepository extends JpaRepository<Plant, Long> {
}
