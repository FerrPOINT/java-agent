package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface McpOAuthFlowRepository extends JpaRepository<McpOAuthFlowEntity, UUID> {

    @org.springframework.data.jpa.repository.Query(
        value = "SELECT java_mcp_oauth_callback_profile(:stateHash, :serverName)", nativeQuery = true)
    Optional<String> callbackProfile(String stateHash, String serverName);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<McpOAuthFlowEntity> findByStateHashAndProfile(String stateHash, String profile);

    Optional<McpOAuthFlowEntity> findByIdAndProfile(UUID id, String profile);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT f FROM McpOAuthFlowEntity f WHERE f.id = :id AND f.profile = :profile")
    Optional<McpOAuthFlowEntity> lockByIdAndProfile(UUID id, String profile);
}
