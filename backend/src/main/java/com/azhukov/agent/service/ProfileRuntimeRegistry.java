package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.ProfileRuntimeStateEntity;
import com.azhukov.agent.persistence.repository.ProfileRuntimeStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

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

    /** Fetch (or lazily create) the runtime row for a profile. */
    public Optional<ProfileRuntimeStateEntity> state(String profile) {
        ProfileRuntimeStateRepository repository = repository();
        if (repository == null) {
            return Optional.empty();
        }
        return repository.findByProfile(profile)
            .or(() -> Optional.of(repository.save(newRow(profile))));
    }

    /** Record the outcome of a reload attempt; bumps revisions only on success. */
    public void recordReload(String profile, boolean success, String error,
                             long configRevision, long toolRegistryRevision, long skillRevision) {
        ProfileRuntimeStateRepository repository = repository();
        if (repository == null) {
            return;
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
    }

    /** Update worker lifecycle state (start/stop/restart/drain, WP-4.8). */
    public boolean updateWorkerState(String profile, String workerState) {
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
    }

    /** Gateway binding state (WP-2 home channel directory link). */
    public void updateGatewayState(String profile, String gatewayState) {
        ProfileRuntimeStateRepository repository = repository();
        if (repository == null) {
            return;
        }
        ProfileRuntimeStateEntity entity = repository.findByProfile(profile)
            .orElseGet(() -> newRow(profile));
        entity.setGatewayState(gatewayState);
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    private ProfileRuntimeStateEntity newRow(String profile) {
        ProfileRuntimeStateEntity entity = new ProfileRuntimeStateEntity();
        entity.setProfile(profile);
        entity.setConfigRevision(0);
        entity.setToolRegistryRevision(0);
        entity.setSkillRevision(0);
        entity.setGatewayState("unbound");
        entity.setWorkerState(WORKER_STOPPED);
        return entity;
    }

    private ProfileRuntimeStateRepository repository() {
        return repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }
}
