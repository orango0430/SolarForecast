package kr.solarcast.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.solarcast.domain.ReplayState;

public interface ReplayStateRepository extends JpaRepository<ReplayState, Long> {
}
