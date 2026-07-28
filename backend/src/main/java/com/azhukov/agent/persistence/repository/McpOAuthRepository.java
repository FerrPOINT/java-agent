package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpOAuthRepository extends JpaRepository<McpOAuthEntity, UUID> {

    Optional<McpOAuthEntity> findByServerName(String serverName);
}