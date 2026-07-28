package com.azhukov.agent.client.mcp;

import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class McpOAuthManager {

    private final McpOAuthRepository mcpOAuthRepository;

    public Optional<String> getToken(String serverName) {
        Optional<McpOAuthEntity> entity = mcpOAuthRepository.findByServerName(serverName);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        McpOAuthEntity token = entity.get();
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(token.getAccessToken());
    }

    public void refreshToken(String serverName) {
        log.warn("OAuth token refresh requested for MCP server {} — not yet implemented", serverName);
    }

    public void storeToken(String serverName, String accessToken, String refreshToken, Instant expiresAt) {
        McpOAuthEntity entity = mcpOAuthRepository.findByServerName(serverName)
            .orElseGet(McpOAuthEntity::new);
        entity.setServerName(serverName);
        entity.setAccessToken(accessToken);
        entity.setRefreshToken(refreshToken);
        entity.setExpiresAt(expiresAt);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        entity.setUpdatedAt(Instant.now());
        mcpOAuthRepository.save(entity);
    }
}