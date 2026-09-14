package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mcp_oauth_tokens")
@Data
public class McpOAuthEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String serverName;

    private String accessToken;

    private String refreshToken;

    private Instant expiresAt;

    private Instant createdAt;

    private Instant updatedAt;

    /** V58: profile scope (legacy rows default to 'default'). */
    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    /** V58: link to the persisted server config (nullable — name-matched at runtime). */
    @Column(name = "server_config_id")
    private UUID serverConfigId;

    /** V58: 0 = plaintext legacy row, 1 = AES-GCM encrypted. */
    @Column(name = "encryption_key_version", nullable = false)
    private int encryptionKeyVersion = 0;

    @Column(name = "token_scope", columnDefinition = "TEXT")
    private String tokenScope;
}