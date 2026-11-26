package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.ProfileRuntimeStateEntity;
import com.azhukov.agent.persistence.repository.ProfileRuntimeStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-profile runtime registry (ADR-013, WP-4). Replaces static
 * {@code gateway_running=false} dashboard JSON: the row records what revision of
 * config / tool registry / skills the running runtime was built from, the
 * gateway binding and the worker state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileRuntimeRegistry {

    public static final String WORKER_RUNNING = "running";
    public static final String WORKER_DRAINING = "draining";
    public static final String WORKER_STOPPED = "stopped";
    public static final String WORKER_FAILED = "failed";

    private final ObjectProvider<ProfileRuntimeStateRepository> repositoryProvider;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public <T> T locked(String profile, Supplier<T> operation) {
        if ("default".equals(profile)) {
            return operation.get();
        }
        synchronized (lockFor(profile)) {
            return operation.get();
        }
    }

    Object lockFor(String profile) {
        return locks.computeIfAbsent(profile, ignored -> new Object());
    }

    public String namedWorkerState(String profile) {
        return find(profile).map(ProfileRuntimeStateEntity::getWorkerState).orElse(WORKER_RUNNING);
    }

    public boolean acceptsRuns(String profile) {
        return "default".equals(profile) || WORKER_RUNNING.equals(namedWorkerState(profile));
    }

    /** Fetch the runtime row without creating it (read-only health/status calls). */
    public Optional<ProfileRuntimeStateEntity> find(String profile) {
        ProfileRuntimeStateRepository repository = repository();
        return repository == null ? Optional.empty() : repository.findByProfile(profile);
    }

    /** Fetch (or lazily create) the runtime row for a profile. */
    public Optional<ProfileRuntimeStateEntity> state(String profile) {
        return locked(profile, () -> {
            ProfileRuntimeStateRepository repository = repository();
            if (repository == null) {
                return Optional.empty();
            }
            return repository.findByProfile(profile)
                .or(() -> Optional.of(repository.save(newRow(profile))));
        });
    }

    /** Record the outcome of a reload attempt; bumps revisions only on success. */
    public void recordReload(String profile, boolean success, String error,
                             long configRevision, long toolRegistryRevision, long skillRevision) {
        locked(profile, () -> {
            ProfileRuntimeStateRepository repository = repository();
            if (repository == null) {
                return null;
            }
            ProfileRuntimeStateEntity entity = repository.findByProfile(profile)
                .orElseGet(() -> newRow(profile));
            entity.setLastReloadStatus(success ? "applied" : "failed");
            entity.setLastReloadError(success ? null : error);
            entity.setLastReloadAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            if (success) {
                entity.setConfigRevision(configRevision);
                entity.setToolRegistryRevision(toolRegistryRevision);
                entity.setSkillRevision(skillRevision);
            }
            repository.save(entity);
            return null;
        });
    }

    /** Record a skill mutation (hub install/update/uninstall, WP-5) — bumps skill revision. */
    public void recordSkillMutation(String profile, String detail) {
        locked(profile, () -> {
            ProfileRuntimeStateRepository repository = repository();
            if (repository == null) {
                return null;
            }
            ProfileRuntimeStateEntity entity = repository.findByProfile(profile)
                .orElseGet(() -> newRow(profile));
            entity.setSkillRevision(entity.getSkillRevision() + 1);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return null;
        });
    }

    /** Update worker lifecycle state (start/stop/restart/drain, WP-4.8). */
    public boolean updateWorkerState(String profile, String workerState) {
        return locked(profile, () -> {
            ProfileRuntimeStateRepository repository = repository();
            if (repository == null) {
                return false;
            }
            ProfileRuntimeStateEntity entity = repository.findByProfile(profile)
                .orElseGet(() -> newRow(profile));
            entity.setWorkerState(workerState);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return true;
        });
    }

    /** Gateway binding state (WP-2 home channel directory link). */
    public void updateGatewayState(String profile, String gatewayState) {
        locked(profile, () -> {
            ProfileRuntimeStateRepository repository = repository();
            if (repository == null) {
                return null;
            }
            ProfileRuntimeStateEntity entity = repository.findByProfile(profile)
                .orElseGet(() -> newRow(profile));
            entity.setGatewayState(gatewayState);
            entity.setUpdatedAt(Instant.now());
            repository.save(entity);
            return null;
        });
    }

    private ProfileRuntimeStateEntity newRow(String profile) {
        ProfileRuntimeStateEntity entity = new ProfileRuntimeStateEntity();
        entity.setProfile(profile);
        entity.setConfigRevision(0);
        entity.setToolRegistryRevision(0);
        entity.setSkillRevision(0);
        entity.setGatewayState("unbound");
        entity.setWorkerState("default".equals(profile) ? WORKER_STOPPED : WORKER_RUNNING);
        return entity;
    }

    private ProfileRuntimeStateRepository repository() {
        return repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }
}
