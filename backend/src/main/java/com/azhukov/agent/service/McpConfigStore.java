package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.McpSchemaCacheEntity;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.azhukov.agent.persistence.repository.McpSchemaCacheRepository;
import com.azhukov.agent.persistence.repository.McpServerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persisted MCP server config store + durable lazy schema cache (WP-3,
 * docs/35). Config writes validate before replacing runtime state; the schema
 * cache is single-flight per server and keeps the last known-good schema on a
 * failed refresh (marked stale) instead of deleting usable tools.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpConfigStore {

    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("stdio", "http", "sse");
    private static final Set<String> ALLOWED_TRUST = Set.of("full", "untrusted");
    private static final Duration SCHEMA_TTL = Duration.ofHours(6);

    private final ObjectProvider<McpServerConfigRepository> configRepositoryProvider;
    private final ObjectProvider<McpSchemaCacheRepository> schemaCacheRepositoryProvider;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<UUID, Object> refreshInFlight = new ConcurrentHashMap<>();

    // ── config CRUD ──────────────────────────────────────────────────────

    public record ServerConfigInput(
        String name, boolean enabled, String transport, String command,
        List<String> args, String baseUrl, List<String> envKeys,
        Map<String, String> headers, List<String> includeTools,
        List<String> excludeTools, double timeoutSeconds, String trust,
        String oauthTokenUrl, String oauthClientId, String oauthScopes) {}

    @Transactional
    public McpServerConfigEntity upsert(String profile, ServerConfigInput input) {
        validate(profile, input);
        McpServerConfigRepository repository = configs();
        String canonProfile = normalizeProfile(profile);
        McpServerConfigEntity entity = repository.findByProfileAndName(canonProfile, input.name())
            .orElseGet(() -> {
                McpServerConfigEntity created = new McpServerConfigEntity();
                created.setProfile(canonProfile);
                created.setName(input.name());
                return created;
            });
        entity.setEnabled(input.enabled());
        entity.setTransport(input.transport());
        entity.setCommand(blankToNull(input.command()));
        entity.setArgsJson(toJson(input.args()));
        entity.setBaseUrl(blankToNull(input.baseUrl()));
        entity.setEnvKeysJson(toJson(input.envKeys()));
        entity.setHeadersJson(toJson(input.headers()));
        entity.setIncludeToolsJson(toJson(input.includeTools()));
        entity.setExcludeToolsJson(toJson(input.excludeTools()));
        entity.setTimeoutSeconds(input.timeoutSeconds());
        entity.setTrust(input.trust() == null ? "full" : input.trust());
        entity.setOauthTokenUrl(blankToNull(input.oauthTokenUrl()));
        entity.setOauthClientId(blankToNull(input.oauthClientId()));
        entity.setOauthScopes(blankToNull(input.oauthScopes()));
        entity.setConfigRevision(entity.getConfigRevision() + 1);
        entity.setUpdatedAt(Instant.now());
        McpServerConfigEntity saved = repository.save(entity);
        invalidateSchemaCache(saved.getId());
        return saved;
    }

    @Transactional
    public boolean delete(String profile, String name) {
        McpServerConfigRepository repository = configs();
        String canonProfile = normalizeProfile(profile);
        Optional<McpServerConfigEntity> existing =
            repository.findByProfileAndName(canonProfile, name);
        if (existing.isEmpty()) {
            return false;
        }
        schemaCache().deleteByServerConfigId(existing.get().getId());
        repository.delete(existing.get());
        return true;
    }

    @Transactional
    public Optional<McpServerConfigEntity> setEnabled(String profile, String name, boolean enabled) {
        McpServerConfigRepository repository = configs();
        return repository.findByProfileAndName(normalizeProfile(profile), name)
            .map(entity -> {
                entity.setEnabled(enabled);
                entity.setConfigRevision(entity.getConfigRevision() + 1);
                entity.setUpdatedAt(Instant.now());
                return repository.save(entity);
            });
    }

    @Transactional(readOnly = true)
    public List<McpServerConfigEntity> list(String profile) {
        return configs().findByProfileOrderByNameAsc(normalizeProfile(profile));
    }

    @Transactional(readOnly = true)
    public Optional<McpServerConfigEntity> find(String profile, String name) {
        return configs().findByProfileAndName(normalizeProfile(profile), name);
    }

    // ── schema cache (single-flight, TTL, stale-keep) ────────────────────

    /** Read the cached schema; empty when absent or config revision changed. */
    @Transactional(readOnly = true)
    public Optional<McpSchemaCacheEntity> cachedSchema(UUID serverConfigId, long configRevision) {
        return schemaCache().findFirstByServerConfigIdOrderByFetchedAtDesc(serverConfigId)
            .filter(cache -> cache.getConfigRevision() == configRevision);
    }

    /**
     * Store a successful discovery result. Single-flight: concurrent callers
     * for the same server collapse onto one store.
     */
    @Transactional
    public void storeSchema(UUID serverConfigId, long configRevision,
                            String toolsJson, String resourcesJson, String promptsJson) {
        Object lock = refreshInFlight.computeIfAbsent(serverConfigId, k -> new Object());
        synchronized (lock) {
            try {
                McpSchemaCacheRepository repository = schemaCache();
                McpSchemaCacheEntity entity = repository
                    .findFirstByServerConfigIdOrderByFetchedAtDesc(serverConfigId)
                    .orElseGet(() -> {
                        McpSchemaCacheEntity created = new McpSchemaCacheEntity();
                        created.setServerConfigId(serverConfigId);
                        return created;
                    });
                entity.setConfigRevision(configRevision);
                entity.setContentHash(sha256(toolsJson));
                entity.setToolsJson(toolsJson);
                entity.setResourcesJson(resourcesJson);
                entity.setPromptsJson(promptsJson);
                entity.setFetchedAt(Instant.now());
                entity.setExpiresAt(Instant.now().plus(SCHEMA_TTL));
                entity.setLastSuccessAt(Instant.now());
                entity.setLastError(null);
                repository.save(entity);
            } finally {
                refreshInFlight.remove(serverConfigId, lock);
            }
        }
    }

    /**
     * Record a failed refresh WITHOUT deleting the prior schema — the stale
     * row stays readable (marked with the error) so usable tools survive.
     */
    @Transactional
    public void recordSchemaError(UUID serverConfigId, long configRevision, String error) {
        schemaCache().findFirstByServerConfigIdOrderByFetchedAtDesc(serverConfigId)
            .ifPresent(entity -> {
                entity.setLastError(error);
                schemaCache().save(entity);
            });
    }

    @Transactional
    public void invalidateSchemaCache(UUID serverConfigId) {
        schemaCache().deleteByServerConfigId(serverConfigId);
    }

    public boolean isFresh(McpSchemaCacheEntity cache) {
        return cache.getExpiresAt() != null && cache.getExpiresAt().isAfter(Instant.now());
    }

    // ── validation ───────────────────────────────────────────────────────

    private void validate(String profile, ServerConfigInput input) {
        if (input == null || isBlank(input.name())) {
            throw new IllegalArgumentException("server name is required");
        }
        if (input.name().length() > 128) {
            throw new IllegalArgumentException("server name is too long");
        }
        String transport = input.transport() == null ? "stdio" : input.transport();
        if (!ALLOWED_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("unsupported transport: " + transport
                + " (allowed: stdio, http, sse)");
        }
        if (input.trust() != null && !ALLOWED_TRUST.contains(input.trust())) {
            throw new IllegalArgumentException("unsupported trust tier: " + input.trust());
        }
        if ("stdio".equals(transport) && isBlank(input.command())) {
            throw new IllegalArgumentException("stdio transport requires a command");
        }
        if (!"stdio".equals(transport) && isBlank(input.baseUrl())) {
            throw new IllegalArgumentException(transport + " transport requires a base_url");
        }
        if (input.timeoutSeconds() < 0) {
            throw new IllegalArgumentException("timeout_seconds must be >= 0");
        }
        if ("http".equals(transport) && !isBlank(input.baseUrl())
            && !input.baseUrl().startsWith("https://") && !input.baseUrl().startsWith("http://")) {
            throw new IllegalArgumentException("base_url must be an http(s) URL");
        }
    }

    private String normalizeProfile(String profile) {
        return isBlank(profile) ? "default" : profile.trim().toLowerCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("value is not serializable: " + e.getMessage());
        }
    }

    public Map<String, Object> redactedView(McpServerConfigEntity entity) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", entity.getName());
        view.put("enabled", entity.isEnabled());
        view.put("transport", entity.getTransport());
        view.put("command", entity.getCommand());
        view.put("args", fromJsonList(entity.getArgsJson()));
        view.put("base_url", entity.getBaseUrl());
        view.put("env_keys", fromJsonList(entity.getEnvKeysJson()));
        view.put("headers", fromJsonMap(entity.getHeadersJson()));
        view.put("include_tools", fromJsonList(entity.getIncludeToolsJson()));
        view.put("exclude_tools", fromJsonList(entity.getExcludeToolsJson()));
        view.put("timeout_seconds", entity.getTimeoutSeconds());
        view.put("trust", entity.getTrust());
        view.put("oauth_configured", entity.getOauthTokenUrl() != null);
        view.put("config_revision", entity.getConfigRevision());
        return view;
    }

    private List<String> fromJsonList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> fromJsonMap(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructMapType(Map.class, String.class, String.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private McpServerConfigRepository configs() {
        McpServerConfigRepository repository = configRepositoryProvider == null
            ? null : configRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("MCP config repository is unavailable");
        }
        return repository;
    }

    private McpSchemaCacheRepository schemaCache() {
        McpSchemaCacheRepository repository = schemaCacheRepositoryProvider == null
            ? null : schemaCacheRepositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new IllegalStateException("MCP schema cache repository is unavailable");
        }
        return repository;
    }
}
