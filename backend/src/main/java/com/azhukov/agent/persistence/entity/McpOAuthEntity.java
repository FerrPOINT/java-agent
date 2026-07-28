package com.azhukov.agent.persistence.entity;

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
}