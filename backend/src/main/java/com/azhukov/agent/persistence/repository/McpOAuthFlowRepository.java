package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface McpOAuthFlowRepository extends JpaRepository<McpOAuthFlowEntity, UUID> {

    Optional<McpOAuthFlowEntity> findByStateHash(String stateHash);
}
