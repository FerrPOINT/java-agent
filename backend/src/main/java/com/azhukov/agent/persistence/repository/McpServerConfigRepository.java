package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface McpServerConfigRepository extends JpaRepository<McpServerConfigEntity, UUID> {

    Optional<McpServerConfigEntity> findByProfileAndName(String profile, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from McpServerConfigEntity c where c.id = :id")
    Optional<McpServerConfigEntity> lockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from McpServerConfigEntity c where c.profile = :profile and c.name = :name")
    Optional<McpServerConfigEntity> lockByProfileAndName(String profile, String name);

    List<McpServerConfigEntity> findByProfileOrderByNameAsc(String profile);

    List<McpServerConfigEntity> findByProfileAndEnabledTrue(String profile);
}
