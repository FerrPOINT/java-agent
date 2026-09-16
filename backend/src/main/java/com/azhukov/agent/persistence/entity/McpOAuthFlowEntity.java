package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** MCP OAuth Authorization Code + PKCE flow state (WP-3, V58). Short-lived. */
@Entity
@Table(name = "mcp_oauth_flows")
@Data
@NoArgsConstructor
public class McpOAuthFlowEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile = "default";

    @Column(name = "server_name", nullable = false, length = 128)
    private String serverName;

    /** SHA-256 of the state parameter — the raw state never persists. */
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Column(name = "challenge_method", nullable = false, length = 16)
    private String challengeMethod = "S256";

    /** AES-GCM encrypted PKCE verifier. */
    @Column(name = "verifier_encrypted", nullable = false, columnDefinition = "TEXT")
    private String verifierEncrypted;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
    private String redirectUri;

    @Column(name = "authorization_url", nullable = false, columnDefinition = "TEXT")
    private String authorizationUrl;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** pending | completed | failed | cancelled | expired. */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "pending";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
