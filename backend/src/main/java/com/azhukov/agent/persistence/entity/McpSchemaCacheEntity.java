package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Durable MCP schema cache row (WP-3, V57). Schemas only, never secrets. */
@Entity
@Table(name = "mcp_schema_cache")
@Data
public class McpSchemaCacheEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "server_config_id", nullable = false)
    private UUID serverConfigId;

    @Column(name = "config_revision", nullable = false)
    private long configRevision;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "tools_json", columnDefinition = "TEXT")
    private String toolsJson;

    @Column(name = "resources_json", columnDefinition = "TEXT")
    private String resourcesJson;

    @Column(name = "prompts_json", columnDefinition = "TEXT")
    private String promptsJson;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
