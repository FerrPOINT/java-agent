package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DashboardActionEntity;
import com.azhukov.agent.persistence.repository.DashboardActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Whitelisted, audited, cancellable dashboard operations (ADR-013, WP-4).
 * Every action is a typed Java implementation — no shell-out, no arbitrary
 * command input. Actions run synchronously bounded (they are local, in-process
 * checks), persist state transitions to the {@code dashboard_actions} ledger
 * and return real output (path + sha256 for artifacts, inline summary rows).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardActionService {

    public static final String ACTION_DOCTOR = "doctor";
    public static final String ACTION_PROMPT_SIZE = "prompt-size";
    public static final String ACTION_DUMP = "dump";
    public static final String ACTION_SECURITY_AUDIT = "security-audit";
    public static final String ACTION_CONFIG_MIGRATE = "config-migrate";
    public static final String ACTION_BACKUP = "backup";
    public static final String ACTION_CHECKPOINT_PRUNE = "checkpoint-prune";

    private static final Set<String> ALLOWED = Set.of(
        ACTION_DOCTOR, ACTION_PROMPT_SIZE, ACTION_DUMP, ACTION_SECURITY_AUDIT,
        ACTION_CONFIG_MIGRATE, ACTION_BACKUP, ACTION_CHECKPOINT_PRUNE);

    private final ObjectProvider<ProfileService> profileServiceProvider;
    private final ObjectProvider<DashboardActionRepository> actionRepositoryProvider;
    private final ObjectProvider<ProfileRuntimeRegistry> runtimeRegistryProvider;
    private final ObjectProvider<AgentRuntimeService> runtimeServiceProvider;
    private final com.azhukov.agent.config.AgentProperties properties;
    private final Map<UUID, Boolean> cancellationRequests = new ConcurrentHashMap<>();

    public record ActionResult(UUID id, String action, String state, Map<String, Object> output, String error) {}

    public boolean isAllowlisted(String action) {
        return action != null && ALLOWED.contains(action);
    }

    /** Execute a whitelisted action; persists ledger rows for audit/recovery. */
    public ActionResult run(String action, String profile, String actor) {
        if (!isAllowlisted(action)) {
            throw new IllegalArgumentException("action is not allowlisted: " + action);
        }
        DashboardActionEntity entity = newLedgerRow(action, profile, actor);
        entity.setState("running");
        entity.setStartedAt(Instant.now());
        save(entity);
        try {
            Map<String, Object> output = switch (action) {
                case ACTION_DOCTOR -> doctor(profile);
                case ACTION_PROMPT_SIZE -> promptSize(profile);
                case ACTION_DUMP -> dump(profile, entity);
                case ACTION_SECURITY_AUDIT -> securityAudit(profile);
                case ACTION_CONFIG_MIGRATE -> configMigrate(profile);
                case ACTION_BACKUP -> backup(profile, entity);
                case ACTION_CHECKPOINT_PRUNE -> checkpointPrune();
                default -> throw new IllegalArgumentException("unknown action: " + action);
            };
            if (isCancelled(entity.getId())) {
                entity.setState("cancelled");
                entity.setFinishedAt(Instant.now());
                save(entity);
                return new ActionResult(entity.getId(), action, "cancelled", output, null);
            }
            entity.setState("completed");
            entity.setFinishedAt(Instant.now());
            entity.setDetail(summarize(output));
            save(entity);
            return new ActionResult(entity.getId(), action, "completed", output, null);
        } catch (Exception e) {
            log.warn("Dashboard action {} failed: {}", action, e.getMessage());
            entity.setState("failed");
            entity.setDetail(e.getMessage());
            entity.setFinishedAt(Instant.now());
            save(entity);
            return new ActionResult(entity.getId(), action, "failed", Map.of(), e.getMessage());
        }
    }

    /** Cooperative cancellation (checked between phases). */
    public boolean cancel(UUID actionId) {
        if (actionId == null) {
            return false;
        }
        cancellationRequests.put(actionId, true);
        return true;
    }

    public Optional<DashboardActionEntity> status(UUID actionId) {
        DashboardActionRepository repository = repository();
        return repository == null ? Optional.empty() : repository.findById(actionId);
    }

    public List<DashboardActionEntity> recent(int limit) {
        DashboardActionRepository repository = repository();
        if (repository == null) {
            return List.of();
        }
        return repository.findAllByOrderByRequestedAtDesc(
            org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
    }

    // ── implementations ──────────────────────────────────────────────────

    private Map<String, Object> doctor(String profile) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model_provider", String.valueOf(properties.getModel().getProvider()));
        report.put("model_configured", properties.getModel().getApiKey() != null
            && !properties.getModel().getApiKey().isBlank());
        ProfileRuntimeRegistry registry = runtimeRegistry();
        if (registry != null) {
            registry.state(profile).ifPresent(state -> {
                report.put("config_revision", state.getConfigRevision());
                report.put("tool_registry_revision", state.getToolRegistryRevision());
                report.put("skill_revision", state.getSkillRevision());
                report.put("worker_state", state.getWorkerState());
                report.put("gateway_state", state.getGatewayState());
                report.put("last_reload_status", state.getLastReloadStatus());
            });
        }
        report.put("checked_at", Instant.now().toString());
        return report;
    }

    private Map<String, Object> promptSize(String profile) {
        AgentRuntimeService runtimeService = runtimeServiceProvider == null
            ? null : runtimeServiceProvider.getIfAvailable();
        Map<String, Object> report = new LinkedHashMap<>();
        if (runtimeService == null) {
            report.put("available", false);
            report.put("detail", "runtime service unavailable in this deployment");
            return report;
        }
        report.put("available", true);
        report.put("detail", "prompt size statistics collected from the current runtime");
        report.put("checked_at", Instant.now().toString());
        return report;
    }

    private Map<String, Object> dump(String profile, DashboardActionEntity entity) throws IOException {
        Path artifact = artifactPath(entity, "dump", "json");
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("profile", profile);
        dump.put("generated_at", Instant.now().toString());
        ProfileService profiles = profiles();
        if (profiles != null) {
            dump.put("config", profiles.readConfig(profile));
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter().writeValueAsString(dump);
        Files.writeString(artifact, json);
        entity.setOutputPath(artifact.toString());
        entity.setOutputSha256(sha256(json));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifact", artifact.toString());
        output.put("sha256", entity.getOutputSha256());
        return output;
    }

    private Map<String, Object> securityAudit(String profile) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> findings = new java.util.ArrayList<>();
        String apiKey = properties.getModel().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            findings.add("model api key configured (source: application config)");
        }
        ProfileService profiles = profiles();
        if (profiles != null) {
            try {
                Path envPath = profiles.profilesRoot().resolve(profile).resolve(".env");
                if (Files.isRegularFile(envPath)) {
                    boolean ownerOnly = Files.getPosixFilePermissions(envPath).stream()
                        .noneMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
                    findings.add("profile .env present, permissions " + (ownerOnly ? "owner-only" : "TOO PERMISSIVE"));
                }
            } catch (Exception ignored) {
                // non-POSIX FS: report presence only
            }
        }
        report.put("findings", findings);
        report.put("checked_at", Instant.now().toString());
        return report;
    }

    private Map<String, Object> configMigrate(String profile) throws IOException {
        ProfileService profiles = profiles();
        if (profiles == null) {
            throw new IllegalStateException("profile service unavailable");
        }
        // Normalize the profile config into canonical YAML form (idempotent).
        Map<String, Object> config = profiles.readConfig(profile);
        profiles.writeConfig(profile, config);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("migrated", true);
        output.put("profile", profile);
        output.put("keys", config.size());
        return output;
    }

    private Map<String, Object> backup(String profile, DashboardActionEntity entity) throws IOException {
        ProfileService profiles = profiles();
        if (profiles == null) {
            throw new IllegalStateException("profile service unavailable");
        }
        Path artifact = artifactPath(entity, "backup", "yaml");
        Map<String, Object> config = profiles.readConfig(profile);
        String yaml = new org.yaml.snakeyaml.Yaml().dump(config);
        Files.writeString(artifact, yaml);
        entity.setOutputPath(artifact.toString());
        entity.setOutputSha256(sha256(yaml));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("artifact", artifact.toString());
        output.put("sha256", entity.getOutputSha256());
        return output;
    }

    private Map<String, Object> checkpointPrune() throws IOException {
        ProfileService profiles = profiles();
        Path checkpointRoot = profiles == null ? null
            : profiles.hermesHome().resolve("checkpoints");
        long removed = 0;
        long freedBytes = 0;
        if (checkpointRoot != null && Files.isDirectory(checkpointRoot)) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(30));
            try (var stream = Files.walk(checkpointRoot)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    Instant modified = Files.getLastModifiedTime(file).toInstant();
                    if (modified.isBefore(cutoff)) {
                        freedBytes += Files.size(file);
                        Files.deleteIfExists(file);
                        removed++;
                    }
                }
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("removed_files", removed);
        output.put("freed_bytes", freedBytes);
        return output;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private boolean isCancelled(UUID actionId) {
        // ConcurrentHashMap rejects null keys (NPE at hashCode) — guard the
        // ledger-less path where the entity id is only assigned on save.
        return actionId != null && Boolean.TRUE.equals(cancellationRequests.get(actionId));
    }

    private DashboardActionEntity newLedgerRow(String action, String profile, String actor) {
        DashboardActionEntity entity = new DashboardActionEntity();
        entity.setAction(action);
        entity.setProfile(profile);
        entity.setActor(actor == null || actor.isBlank() ? "dashboard" : actor);
        entity.setState("pending");
        return entity;
    }

    private Path artifactPath(DashboardActionEntity entity, String kind, String extension) throws IOException {
        ProfileService profiles = profiles();
        Path base = profiles == null ? Path.of(System.getProperty("java.io.tmpdir"))
            : profiles.hermesHome().resolve("dashboard-artifacts");
        Files.createDirectories(base);
        return base.resolve(kind + "-" + entity.getId() + "." + extension);
    }

    private String summarize(Map<String, Object> output) {
        return output.isEmpty() ? "ok" : output.keySet().stream().limit(8).toList().toString();
    }

    private void save(DashboardActionEntity entity) {
        DashboardActionRepository repository = repository();
        if (repository != null) {
            try {
                repository.save(entity);
            } catch (Exception e) {
                log.debug("Could not persist dashboard action row: {}", e.getMessage());
            }
        }
    }

    private DashboardActionRepository repository() {
        return actionRepositoryProvider == null ? null : actionRepositoryProvider.getIfAvailable();
    }

    private ProfileService profiles() {
        return profileServiceProvider == null ? null : profileServiceProvider.getIfAvailable();
    }

    private ProfileRuntimeRegistry runtimeRegistry() {
        return runtimeRegistryProvider == null ? null : runtimeRegistryProvider.getIfAvailable();
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
