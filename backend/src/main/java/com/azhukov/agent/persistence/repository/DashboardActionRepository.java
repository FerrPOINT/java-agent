package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.DashboardActionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardActionRepository extends JpaRepository<DashboardActionEntity, UUID> {

    Optional<DashboardActionEntity> findFirstByActionOrderByRequestedAtDesc(String action);

    List<DashboardActionEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);

    List<DashboardActionEntity> findByStateOrderByRequestedAtAsc(String state);
}
