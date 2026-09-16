package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Profile-scoped persisted MCP server config (WP-3, V57). Non-secret fields only. */
@Entity
@Table(name = "mcp_server_configs")
@Data
public class McpServerConfigEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** stdio | http | sse. */
    @Column(name = "transport", nullable = false, length = 16)
    private String transport = "stdio";

    @Column(name = "command", columnDefinition = "TEXT")
    private String command;

    /** JSON array of args. */
    @Column(name = "args", columnDefinition = "TEXT")
    private String argsJson;

    @Column(name = "base_url", columnDefinition = "TEXT")
    private String baseUrl;

    /** JSON array of env KEY NAMES (references only, never values). */
    @Column(name = "env_keys", columnDefinition = "TEXT")
    private String envKeysJson;

    /** JSON object of header names → values (headers are not secrets). */
    @Column(name = "headers", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "include_tools", columnDefinition = "TEXT")
    private String includeToolsJson;

    @Column(name = "exclude_tools", columnDefinition = "TEXT")
    private String excludeToolsJson;

    @Column(name = "timeout_seconds", nullable = false)
    private double timeoutSeconds;

    /** full | untrusted — unknown values fail closed at use sites. */
    @Column(name = "trust", nullable = false, length = 16)
    private String trust = "full";

    @Column(name = "oauth_token_url", columnDefinition = "TEXT")
    private String oauthTokenUrl;

    @Column(name = "oauth_client_id", columnDefinition = "TEXT")
    private String oauthClientId;

    @Column(name = "oauth_scopes", columnDefinition = "TEXT")
    private String oauthScopes;

    @Column(name = "config_revision", nullable = false)
    private long configRevision = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
