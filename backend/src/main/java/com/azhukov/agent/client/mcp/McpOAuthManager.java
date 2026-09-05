package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.McpOAuthEntity;
import com.azhukov.agent.persistence.repository.McpOAuthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class McpOAuthManager {

    private final McpOAuthRepository mcpOAuthRepository;
    private final AgentProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // Credential stripping pattern for error logging
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(?:"
        + "ghp_[A-Za-z0-9_]{1,255}"
        + "|sk-[A-Za-z0-9_]{1,255}"
        + "|Bearer\\s+\\S+"
        + "|token=[^\\s&,;\"']{1,255}"
        + "|key=[^\\s&,;\"']{1,255}"
        + "|API_KEY=[^\\s&,;\"']{1,255}"
        + "|password=[^\\s&,;\"']{1,255}"
        + "|secret=[^\\s&,;\"']{1,255}"
        + ")",
        Pattern.CASE_INSENSITIVE
    );

    public Optional<String> getToken(String serverName) {
        Optional<McpOAuthEntity> entity = mcpOAuthRepository.findByServerName(serverName);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        McpOAuthEntity token = entity.get();
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            // Token expired — attempt refresh automatically
            try {
                refreshToken(serverName);
            } catch (InterruptedException e) {
                // P2-17: Restore interrupt status rather than silently swallowing
                Thread.currentThread().interrupt();
                log.warn("Auto-refresh of OAuth token for MCP server {} interrupted", serverName);
            } catch (Exception e) {
                log.warn("Auto-refresh of OAuth token for MCP server {} failed: {}", serverName, e.getMessage());
            }
            // Re-read after refresh attempt
            Optional<McpOAuthEntity> refreshed = mcpOAuthRepository.findByServerName(serverName);
            if (refreshed.isPresent() && refreshed.get().getExpiresAt() != null
                && refreshed.get().getExpiresAt().isAfter(Instant.now())) {
                return Optional.of(refreshed.get().getAccessToken());
            }
            return Optional.empty();
        }
        return Optional.of(token.getAccessToken());
    }

    /**
     * Refresh the OAuth token for the given MCP server using the stored refresh token.
     * Makes a POST request to the server's configured OAuth token URL with grant_type=refresh_token.
     *
     * @param serverName the MCP server name
     * @throws IllegalStateException if no stored token or no refresh token exists
     * @throws IOException if the token refresh HTTP request fails
     */
    public void refreshToken(String serverName) throws IOException, InterruptedException {
        // Find the server config to get OAuth endpoint details
        AgentProperties.McpProperties.ServerProperties serverConfig = properties.getMcp().getServers().stream()
            .filter(s -> s.getName().equals(serverName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No MCP server config found for: " + serverName));

        String tokenUrl = serverConfig.getOauthTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new IllegalStateException(
                "No OAuth token URL configured for MCP server: " + serverName);
        }

        McpOAuthEntity entity = mcpOAuthRepository.findByServerName(serverName)
            .orElseThrow(() -> new IllegalStateException(
                "No stored OAuth token for MCP server: " + serverName));

        if (entity.getRefreshToken() == null || entity.getRefreshToken().isBlank()) {
            throw new IllegalStateException(
                "No refresh token stored for MCP server: " + serverName);
        }

        // Build form-encoded POST body per RFC 6749 §6
        StringBuilder body = new StringBuilder();
        body.append("grant_type=refresh_token");
        body.append("&refresh_token=").append(java.net.URLEncoder.encode(entity.getRefreshToken(), java.nio.charset.StandardCharsets.UTF_8));
        if (serverConfig.getOauthClientId() != null && !serverConfig.getOauthClientId().isBlank()) {
            body.append("&client_id=").append(java.net.URLEncoder.encode(serverConfig.getOauthClientId(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (serverConfig.getOauthClientSecret() != null && !serverConfig.getOauthClientSecret().isBlank()) {
            body.append("&client_secret=").append(java.net.URLEncoder.encode(serverConfig.getOauthClientSecret(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (serverConfig.getOauthScopes() != null && !serverConfig.getOauthScopes().isBlank()) {
            body.append("&scope=").append(java.net.URLEncoder.encode(serverConfig.getOauthScopes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(30))
            .build();

        log.info("Refreshing OAuth token for MCP server {} from {}", serverName, tokenUrl);

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String sanitized = sanitizeError(response.body());
            throw new IOException("OAuth token refresh failed for " + serverName
                + " with status " + response.statusCode() + ": " + sanitized);
        }

        // Parse JSON response manually (avoid pulling in Jackson dependency here for a simple parse)
        String responseBody = response.body();
        String newAccessToken = extractJsonField(responseBody, "access_token");
        String newRefreshToken = extractJsonField(responseBody, "refresh_token");
        String expiresInStr = extractJsonField(responseBody, "expires_in");

        if (newAccessToken == null || newAccessToken.isBlank()) {
            throw new IOException("OAuth token refresh response missing access_token for " + serverName);
        }

        // If no new refresh token provided, keep the old one
        if (newRefreshToken == null || newRefreshToken.isBlank()) {
            newRefreshToken = entity.getRefreshToken();
        }

        Instant expiresAt = Instant.now().plusSeconds(3600); // default 1h
        if (expiresInStr != null && !expiresInStr.isBlank()) {
            try {
                expiresAt = Instant.now().plusSeconds(Long.parseLong(expiresInStr.trim()));
            } catch (NumberFormatException e) {
                log.warn("Invalid expires_in value '{}' for MCP server {}, using default", expiresInStr, serverName);
            }
        }

        storeToken(serverName, newAccessToken, newRefreshToken, expiresAt);
        log.info("Successfully refreshed OAuth token for MCP server {}", serverName);
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

    /** Extract a string field value from a JSON response body (simple parser, no Jackson needed). */
    static String extractJsonField(String json, String fieldName) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        // Search for "fieldName":"value" pattern
        String needle = "\"" + fieldName + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int colonIdx = json.indexOf(":", idx + needle.length());
        if (colonIdx < 0) {
            return null;
        }
        // Skip whitespace after colon
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length()) {
            return null;
        }
        char firstChar = json.charAt(valueStart);
        if (firstChar == '"') {
            // String value
            int valueEnd = valueStart + 1;
            StringBuilder sb = new StringBuilder();
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if (c == '\\' && valueEnd + 1 < json.length()) {
                    char next = json.charAt(valueEnd + 1);
                    // M36 fix: decode JSON unicode escapes instead of
                    // dropping the backslash and keeping literal "uXXXX".
                    if (next == 'u' && valueEnd + 5 < json.length()) {
                        String hex = json.substring(valueEnd + 2, valueEnd + 6);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            valueEnd += 6;
                        } catch (NumberFormatException nfe) {
                            // Not a valid escape — keep as-is.
                            sb.append(next);
                            valueEnd += 2;
                        }
                    } else {
                        sb.append(next);
                        valueEnd += 2;
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    valueEnd++;
                }
            }
            return sb.toString();
        } else {
            // Numeric or other value — read until comma or closing brace
            int valueEnd = valueStart;
            while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd).trim();
        }
    }

    static String sanitizeError(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CREDENTIAL_PATTERN.matcher(text).replaceAll("[REDACTED]");
    }
}