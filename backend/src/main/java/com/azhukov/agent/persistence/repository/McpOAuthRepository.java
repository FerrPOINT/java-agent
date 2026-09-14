package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpOAuthRepository extends JpaRepository<McpOAuthEntity, UUID> {

    /** Legacy name-only lookup — DO NOT use for auth decisions (V63: profile-scoped). */
    @Deprecated
    Optional<McpOAuthEntity> findByServerName(String serverName);

    /** Profile-scoped token resolution (upstream 399238f2c2 parity). */
    Optional<McpOAuthEntity> findByProfileAndServerName(String profile, String serverName);
}
