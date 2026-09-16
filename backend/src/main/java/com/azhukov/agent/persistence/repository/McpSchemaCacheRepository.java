package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface McpSchemaCacheRepository extends JpaRepository<McpSchemaCacheEntity, UUID> {

    Optional<McpSchemaCacheEntity> findFirstByServerConfigIdOrderByFetchedAtDesc(UUID serverConfigId);

    void deleteByServerConfigId(UUID serverConfigId);
}
