package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpServerConfigRepository extends JpaRepository<McpServerConfigEntity, UUID> {

    Optional<McpServerConfigEntity> findByProfileAndName(String profile, String name);

    List<McpServerConfigEntity> findByProfileOrderByNameAsc(String profile);

    List<McpServerConfigEntity> findByProfileAndEnabledTrue(String profile);
}
