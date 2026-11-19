package com.azhukov.agent.client.mcp;

import com.azhukov.agent.config.AgentProperties.McpProperties.ServerProperties;
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

    public Optional<String> getToken(String profile, ServerProperties server) {
        String serverName = server.getName();
        Optional<McpOAuthEntity> entity =
            mcpOAuthRepository.findByProfileAndServerName(profile, serverName);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        McpOAuthEntity token = entity.get();
        if (!issuerMatches(token, server.getOauthTokenUrl())) {
            log.warn("Stored OAuth issuer no longer matches MCP server {}; re-authorization required", serverName);
            return Optional.empty();
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            // Token expired — attempt refresh automatically
            try {
                refreshToken(profile, server);
            } catch (InterruptedException e) {
                // P2-17: Restore interrupt status rather than silently swallowing
                Thread.currentThread().interrupt();
                log.warn("Auto-refresh of OAuth token for MCP server {} interrupted", serverName);
            } catch (Exception e) {
                log.warn("Auto-refresh of OAuth token for MCP server {} failed: {}", serverName, e.getMessage());
            }
            // Re-read after refresh attempt
            Optional<McpOAuthEntity> refreshed =
                mcpOAuthRepository.findByProfileAndServerName(profile, serverName);
            if (refreshed.isPresent() && issuerMatches(refreshed.get(), server.getOauthTokenUrl())
                && refreshed.get().getExpiresAt() != null && refreshed.get().getExpiresAt().isAfter(Instant.now())) {
                return Optional.ofNullable(refreshed.get().getAccessToken()).filter(value -> !value.isBlank());
            }
            return Optional.empty();
        }
        return Optional.ofNullable(token.getAccessToken()).filter(value -> !value.isBlank());
    }

    private static boolean issuerMatches(McpOAuthEntity token, String tokenUrl) {
        String storedIssuer = token.getTokenIssuer();
        if (storedIssuer == null || storedIssuer.isBlank()) return true;
        return tokenUrl != null && !tokenUrl.isBlank() && storedIssuer.equals(issuerOf(tokenUrl));
    }

    /** Refresh the stored credential using the selected MCP server configuration. */
    public void refreshToken(String profile, ServerProperties serverConfig) throws IOException, InterruptedException {
        String serverName = serverConfig.getName();
        String tokenUrl = serverConfig.getOauthTokenUrl();
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new IllegalStateException(
                "No OAuth token URL configured for MCP server: " + serverName);
        }
        String currentIssuer = issuerOf(tokenUrl);

        McpOAuthEntity entity = mcpOAuthRepository.findByProfileAndServerName(profile, serverName)
            .orElseThrow(() -> new IllegalStateException(
                "No stored OAuth token for MCP server: " + serverName));

        if (entity.getRefreshToken() == null || entity.getRefreshToken().isBlank()) {
            throw new IllegalStateException(
                "No refresh token stored for MCP server: " + serverName);
        }

        // WP-e (V67): a refresh token minted by issuer A must never be replayed
        // at issuer B (protected-resource metadata edit, server migration). A
        // mismatched issuer invalidates the stored token instead of leaking it.
        String storedIssuer = entity.getTokenIssuer();
        if (storedIssuer != null && !storedIssuer.isBlank() && !storedIssuer.equals(currentIssuer)) {
            mcpOAuthRepository.delete(entity);
            throw new IllegalStateException(
                "Stored OAuth token for MCP server " + serverName
                + " was issued by " + storedIssuer + " but the server now points at "
                + currentIssuer + " — token discarded; re-authorization required");
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
            throw new IOException("OAuth token refresh failed for " + serverName
                + " with status " + response.statusCode());
        }

        String responseBody = response.body();
        var responseJson = JSON.readTree(responseBody);
        var refreshField = responseJson == null ? null : responseJson.get("refresh_token");
        if (refreshField != null && !refreshField.isNull()
                && (!refreshField.isTextual() || refreshField.asText().isBlank())) {
            throw new IOException("OAuth token refresh response has invalid refresh_token for " + serverName);
        }
        String newAccessToken = extractJsonField(responseJson, "access_token");
        String newRefreshToken = extractJsonField(responseJson, "refresh_token");
        String expiresInStr = extractJsonField(responseJson, "expires_in");

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

        storeToken(profile, serverName, newAccessToken, newRefreshToken, expiresAt, tokenUrl);
        log.info("Successfully refreshed OAuth token for MCP server {}", serverName);
    }

    /** V67: issuer identity = token endpoint origin (scheme + host + port). */
    static String issuerOf(String tokenUrl) {
        try {
            java.net.URL url = new java.net.URI(tokenUrl).toURL();
            String port = url.getPort() == -1 ? "" : ":" + url.getPort();
            return url.getProtocol() + "://" + url.getHost() + port;
        } catch (Exception e) {
            return tokenUrl; // opaque fallback: compare raw strings
        }
    }

    /** Store the token and its issuer under the same profile-scoped connection. */
    public void storeToken(String profile, String serverName, String accessToken,
                           String refreshToken, Instant expiresAt, String tokenUrl) {
        McpOAuthEntity entity = mcpOAuthRepository.findByProfileAndServerName(profile, serverName)
            .orElseGet(McpOAuthEntity::new);
        entity.setProfile(profile);
        entity.setServerName(serverName);
        entity.setAccessToken(accessToken);
        entity.setRefreshToken(refreshToken);
        entity.setExpiresAt(expiresAt);
        entity.setTokenIssuer(issuerOf(tokenUrl));
        Instant now = Instant.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        mcpOAuthRepository.save(entity);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Read a top-level OAuth response scalar; malformed or structured values are rejected. */
    public static String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) return null;
        try {
            return extractJsonField(JSON.readTree(json), fieldName);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String extractJsonField(com.fasterxml.jackson.databind.JsonNode root, String fieldName) {
        var value = root != null && root.isObject() ? root.get(fieldName) : null;
        boolean tokenField = "access_token".equals(fieldName) || "refresh_token".equals(fieldName);
        return value != null && (value.isTextual() || (!tokenField && value.isNumber())) ? value.asText() : null;
    }

    public static String sanitizeError(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CREDENTIAL_PATTERN.matcher(text).replaceAll("[REDACTED]");
    }
}