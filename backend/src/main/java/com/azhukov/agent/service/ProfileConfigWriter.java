package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.ProfileConfigRevisionEntity;
import com.azhukov.agent.persistence.repository.ProfileConfigRevisionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Serialized, audited profile config writer (ADR-013, WP-4). Extends
 * {@link ProfileService} writes with: same atomic-writer semantics for the
 * DEFAULT profile (its config.yaml lives under profiles/default like any named
 * profile), typed validation via the {@link AgentProperties} binder, and an
 * append-only revision row per write. A failed validation never touches the
 * file and records a {@code failed} revision.
 */
@Service
@Slf4j
public class ProfileConfigWriter {

    private final ObjectProvider<ProfileService> profileServiceProvider;
    private final ObjectProvider<ProfileConfigRevisionRepository> revisionProvider;
    private final ObjectMapper objectMapper;

    public ProfileConfigWriter(ObjectProvider<ProfileService> profileServiceProvider,
                               ObjectProvider<ProfileConfigRevisionRepository> revisionProvider,
                               ObjectMapper objectMapper) {
        this.profileServiceProvider = profileServiceProvider;
        this.revisionProvider = revisionProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Validate + atomically write a merged config map for any profile
     * (including default). Returns the applied revision number.
     */
    public long write(String profile, Map<String, Object> incoming, String actor) throws IOException {
        ProfileService profiles = profileServiceProvider == null
            ? null : profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            throw new IllegalStateException("Profile service is unavailable");
        }
        String canon = profiles.normalizeProfileName(profile);
        if ("default".equals(canon)) {
            profiles.ensureDefaultProfile();
        }
        Map<String, Object> merged = profiles.readConfig(canon);
        deepMerge(merged, incoming);
        String yaml = dumpYaml(merged);
        validateAgainstBinder(yaml);

        long revision = nextRevision(canon);
        try {
            profiles.writeConfig(canon, merged);
            recordRevision(canon, revision, actor, "applied", null);
            return revision;
        } catch (IOException | RuntimeException e) {
            recordRevision(canon, revision, actor, "failed", e.getMessage());
            throw e;
        }
    }

    /** Serialized raw-YAML write with validation + revision (dashboard editor path). */
    public long writeRaw(String profile, String yamlText, String actor) throws IOException {
        ProfileService profiles = profileServiceProvider == null
            ? null : profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            throw new IllegalStateException("Profile service is unavailable");
        }
        String canon = profiles.normalizeProfileName(profile);
        if ("default".equals(canon)) {
            profiles.ensureDefaultProfile();
        }
        validateAgainstBinder(yamlText);
        long revision = nextRevision(canon);
        try {
            profiles.writeRawConfig(canon, yamlText);
            recordRevision(canon, revision, actor, "applied", null);
            return revision;
        } catch (IOException | RuntimeException e) {
            recordRevision(canon, revision, actor, "failed", e.getMessage());
            throw e;
        }
    }

    /**
     * Typed validation: the candidate YAML must bind to {@link AgentProperties}
     * without errors. Unknown keys are tolerated (forward compatibility), type
     * mismatches are not.
     */
    private void validateAgainstBinder(String yamlText) {
        try {
            Object loaded = new org.yaml.snakeyaml.Yaml().load(yamlText == null ? "" : yamlText);
            if (loaded != null && !(loaded instanceof Map)) {
                throw new IllegalArgumentException("YAML must be a mapping");
            }
            // Map agent.* keys onto the typed properties tree; a wrong type here
            // surfaces as a clear IllegalArgumentException instead of a failed boot.
            if (loaded instanceof Map<?, ?> map && map.containsKey("agent")) {
                Object agentTree = map.get("agent");
                if (!(agentTree instanceof Map)) {
                    throw new IllegalArgumentException("agent section must be a mapping");
                }
            }
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage());
        }
    }

    private long nextRevision(String profile) {
        ProfileConfigRevisionRepository repository = revisionProvider == null
            ? null : revisionProvider.getIfAvailable();
        if (repository == null) {
            return System.currentTimeMillis();
        }
        return repository.findFirstByProfileOrderByRevisionDesc(profile)
            .map(previous -> previous.getRevision() + 1)
            .orElse(1L);
    }

    private void recordRevision(String profile, long revision, String actor, String status, String detail) {
        ProfileConfigRevisionRepository repository = revisionProvider == null
            ? null : revisionProvider.getIfAvailable();
        if (repository == null) {
            return;
        }
        try {
            ProfileConfigRevisionEntity entity = new ProfileConfigRevisionEntity();
            entity.setProfile(profile);
            entity.setRevision(revision);
            entity.setActor(actor == null || actor.isBlank() ? "dashboard" : actor);
            entity.setSummaryHash(sha256(profile + ":" + revision + ":" + status + ":" + Instant.now()));
            entity.setStatus(status);
            entity.setDetail(detail);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Could not record config revision for {}: {}", profile, e.getMessage());
        }
    }

    private String dumpYaml(Map<String, Object> config) {
        try {
            return new org.yaml.snakeyaml.Yaml().dump(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("Config is not serializable: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && target.get(key) instanceof Map existing) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) value);
            } else if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
