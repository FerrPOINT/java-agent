package com.azhukov.agent.service;

import com.azhukov.agent.client.mcp.McpOAuthManager;
import com.azhukov.agent.persistence.entity.McpOAuthFlowEntity;
import com.azhukov.agent.persistence.repository.McpOAuthFlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP OAuth Authorization Code + PKCE flow (WP-3, docs/35 item 6).
 *
 * <p>Only for servers whose persisted config carries OAuth metadata
 * (oauth_token_url + oauth_client_id). Flow state is short-lived (10 min),
 * the raw state value never persists (SHA-256 hash only), the PKCE verifier
 * is stored AES-GCM encrypted, the callback validates state + single-use +
 * expiry before exchanging the code, and token values are never returned to
 * the dashboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpOAuthFlowService {

    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<McpOAuthFlowRepository> flowRepositoryProvider;
    private final ObjectProvider<McpConfigStore> configStoreProvider;
    private final ObjectProvider<TokenSecretCipher> cipherProvider;
    private final ObjectProvider<McpOAuthManager> oauthManagerProvider;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public record FlowStart(String flowId, String authorizationUrl, String expiresIn) {}

    @Transactional
    public FlowStart start(String profile, String serverName, String redirectBase) {
        McpConfigStore store = configStore();
        var config = store.find(profile, serverName)
            .orElseThrow(() -> new IllegalArgumentException(
                "MCP server '" + serverName + "' is not configured"));
        if (config.getOauthTokenUrl() == null || config.getOauthClientId() == null) {
            throw new IllegalArgumentException(
                "MCP server '" + serverName + "' has no OAuth metadata (token url / client id)");
        }

        byte[] stateBytes = new byte[32];
        RANDOM.nextBytes(stateBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        byte[] verifierBytes = new byte[64];
        RANDOM.nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        String challenge = s256(verifier);

        String authorizationUrl = authorizationUrl(config, state, challenge, redirectBase);
        String stateHash = sha256Hex(state);

        McpOAuthFlowEntity flow = new McpOAuthFlowEntity();
        flow.setProfile(normalize(profile));
        flow.setServerName(serverName);
        flow.setStateHash(stateHash);
        flow.setCodeChallenge(challenge);
        flow.setChallengeMethod("S256");
        flow.setVerifierEncrypted(cipher().encrypt(verifier));
        flow.setRedirectUri(redirectUri(redirectBase, serverName));
        flow.setAuthorizationUrl(authorizationUrl);
        flow.setExpiresAt(Instant.now().plus(FLOW_TTL));
        flow.setStatus("pending");
        flow = flows().save(flow);

        log.info("Started MCP OAuth flow for server {} (flow {})", serverName, flow.getId());
        return new FlowStart(flow.getId().toString(), authorizationUrl,
            String.valueOf(FLOW_TTL.toSeconds()));
    }

    @Transactional
    public Map<String, Object> complete(String serverName, String code, String state) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return failure("missing state or code parameter");
        }
        McpOAuthFlowEntity flow = flows().findByStateHash(sha256Hex(state))
            .orElse(null);
        if (flow == null || !"pending".equals(flow.getStatus())) {
            return failure("unknown or already used state");
        }
        if (flow.getExpiresAt().isBefore(Instant.now())) {
            flow.setStatus("expired");
            flows().save(flow);
            return failure("flow expired, restart authorization");
        }
        if (!flow.getServerName().equals(serverName)) {
            flow.setStatus("failed");
            flows().save(flow);
            return failure("state does not match this server");
        }

        McpConfigStore store = configStore();
        var config = store.find(flow.getProfile(), flow.getServerName()).orElse(null);
        if (config == null || config.getOauthTokenUrl() == null || config.getOauthClientId() == null) {
            flow.setStatus("failed");
            flows().save(flow);
            return failure("server OAuth configuration disappeared");
        }

        String verifier = cipher().decrypt(flow.getVerifierEncrypted());
        String tokenResponse = exchangeCode(config, code, verifier, flow.getRedirectUri());

        String accessToken = McpOAuthManager.extractJsonField(tokenResponse, "access_token");
        String refreshToken = McpOAuthManager.extractJsonField(tokenResponse, "refresh_token");
        String expiresIn = McpOAuthManager.extractJsonField(tokenResponse, "expires_in");
        if (accessToken == null || accessToken.isBlank()) {
            flow.setStatus("failed");
            flows().save(flow);
            return failure("authorization server returned no access_token");
        }
        long ttl = 3600;
        try {
            if (expiresIn != null && !expiresIn.isBlank()) {
                ttl = Long.parseLong(expiresIn.trim());
            }
        } catch (NumberFormatException ignored) {
            // default 1h
        }

        McpOAuthManager oauth = oauth();
        oauth.storeToken(flow.getServerName(), accessToken, refreshToken, Instant.now().plusSeconds(ttl));

        flow.setStatus("completed");
        flows().save(flow);
        log.info("MCP OAuth flow for server {} completed", flow.getServerName());
        return Map.of("ok", true, "status", "completed", "expires_in", ttl);
    }

    @Transactional
    public boolean cancel(String flowId) {
        try {
            UUID id = UUID.fromString(flowId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        Optional<McpOAuthFlowEntity> flow = flows().findById(UUID.fromString(flowId));
        if (flow.isEmpty() || !"pending".equals(flow.get().getStatus())) {
            return false;
        }
        flow.get().setStatus("cancelled");
        flows().save(flow.get());
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<McpOAuthFlowEntity> status(String flowId) {
        try {
            return flows().findById(UUID.fromString(flowId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private String exchangeCode(com.azhukov.agent.persistence.entity.McpServerConfigEntity config,
                              String code, String verifier, String redirectUri) {
        String body = "grant_type=authorization_code"
            + "&code=" + url(code)
            + "&redirect_uri=" + url(redirectUri)
            + "&client_id=" + url(config.getOauthClientId())
            + "&code_verifier=" + url(verifier);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getOauthTokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("token exchange failed with status "
                    + response.statusCode() + ": "
                    + McpOAuthManager.sanitizeError(response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("token exchange interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("token exchange failed: " + e.getMessage(), e);
        }
    }

    private String authorizationUrl(com.azhukov.agent.persistence.entity.McpServerConfigEntity config,
                                 String state, String challenge, String redirectBase) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", config.getOauthClientId());
        params.put("redirect_uri", redirectUri(redirectBase, config.getName()));
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        if (config.getOauthScopes() != null && !config.getOauthScopes().isBlank()) {
            params.put("scope", config.getOauthScopes());
        }
        String query = params.entrySet().stream()
            .map(e -> e.getKey() + "=" + url(e.getValue()))
            .reduce((a, b) -> a + "&" + b).orElse("");
        // Authorization endpoint convention: token url path segment "token" -> "authorize".
        String tokenUrl = config.getOauthTokenUrl();
        String authUrl = tokenUrl.endsWith("/token")
            ? tokenUrl.substring(0, tokenUrl.length() - "token".length()) + "authorize"
            : tokenUrl + "/authorize";
        return authUrl + (authUrl.contains("?") ? "&" : "?") + query;
    }

    private String redirectUri(String redirectBase, String serverName) {
        String base = redirectBase == null || redirectBase.isBlank()
            ? "" : redirectBase.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/mcp/oauth/callback/" + serverName;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("PKCE challenge derivation failed", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("state hash failed", e);
        }
    }

    private static Map<String, Object> failure(String detail) {
        return Map.of("ok", false, "status", "failed", "detail", detail);
    }

    private static String normalize(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile.trim().toLowerCase();
    }

    private McpOAuthFlowRepository flows() {
        McpOAuthFlowRepository repository = flowRepositoryProvider == null
            ? null : flowRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("MCP OAuth flow repository is unavailable");
        }
        return repository;
    }

    private McpConfigStore configStore() {
        McpConfigStore store = configStoreProvider == null ? null : configStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException("MCP config store is unavailable");
        }
        return store;
    }

    private TokenSecretCipher cipher() {
        TokenSecretCipher cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
        if (cipher == null) {
            throw new IllegalStateException("token cipher is unavailable");
        }
        return cipher;
    }

    private McpOAuthManager oauth() {
        McpOAuthManager manager = oauthManagerProvider == null ? null : oauthManagerProvider.getIfAvailable();
        if (manager == null) {
            throw new IllegalStateException("MCP OAuth manager is unavailable");
        }
        return manager;
    }
}
