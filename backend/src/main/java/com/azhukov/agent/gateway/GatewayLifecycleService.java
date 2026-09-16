package com.azhukov.agent.gateway;

import com.azhukov.agent.persistence.entity.GatewayRuntimeStateEntity;
import com.azhukov.agent.persistence.repository.GatewayRuntimeStateRepository;
import com.azhukov.agent.service.GatewayHomeChannelService;
import com.azhukov.agent.service.ProfileRuntimeRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway lifecycle state machine (ADR-012, WP-2): RUNNING / DRAINING /
 * STOPPED (+ FAILED terminal-with-cause). In-memory authority, persisted view
 * in {@code gateway_runtime_state} for dashboard reads across restarts.
 *
 * <p>Manages Java adapters only — never arbitrary OS commands. {@code drain()}
 * stops accepting new inbound dispatch, waits a bounded time for in-flight
 * work, then reports what is still active.
 */
@Service
@Slf4j
public class GatewayLifecycleService {

    public enum State { RUNNING, DRAINING, STOPPED, FAILED }

    private final GatewayRuntimeStateRepository stateRepository;
    private final ProfileRuntimeRegistry profileRuntimeRegistry;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicInteger activeInbound = new AtomicInteger();
    private final Instant startedAt = Instant.now();
    private volatile String lastError;

    /** Legacy constructor for focused lifecycle tests. */
    public GatewayLifecycleService(@Autowired(required = false) GatewayRuntimeStateRepository stateRepository) {
        this(stateRepository, null);
    }

    @Autowired
    public GatewayLifecycleService(
        @Autowired(required = false) GatewayRuntimeStateRepository stateRepository,
        @Autowired(required = false) ProfileRuntimeRegistry profileRuntimeRegistry
    ) {
        this.stateRepository = stateRepository;
        this.profileRuntimeRegistry = profileRuntimeRegistry;
    }

    public State currentState() {
        return state.get();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /** Whether inbound dispatch is accepted right now (false while draining/stopped). */
    public boolean acceptsInbound() {
        return state.get() == State.RUNNING;
    }

    /** Bounded wait used by drain(); virtual-thread friendly (no lock held). */
    public boolean beginInbound() {
        while (true) {
            State current = state.get();
            if (current != State.RUNNING) {
                return false;
            }
            if (state.compareAndSet(current, current)) {
                activeInbound.incrementAndGet();
                return true;
            }
        }
    }

    public void endInbound() {
        activeInbound.decrementAndGet();
    }

    public int activeInboundCount() {
        return activeInbound.get();
    }

    public Instant startedAt() {
        return startedAt;
    }

    public String lastError() {
        return lastError;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startOnApplicationReady() {
        // The backend owns the HTTP gateway adapters; without this transition
        // every inbound event is rejected as STOPPED after a JVM restart.
        start();
    }

    /** RUNNING. Idempotent: start on RUNNING is a no-op returning current status. */
    public synchronized Status start() {
        State previous = state.get();
        state.set(State.RUNNING);
        lastError = null;
        persist(State.RUNNING, null);
        if (previous != State.RUNNING) {
            log.info("Gateway lifecycle: {} -> RUNNING", previous);
        }
        return status();
    }

    /**
     * Drain: stop new inbound, wait bounded time for in-flight work. Returns
     * drain report; state becomes DRAINING for the wait, then STOPPED.
     */
    public synchronized Status drain(Duration bound) {
        if (state.get() == State.STOPPED) {
            return status();
        }
        state.set(State.DRAINING);
        persist(State.DRAINING, lastError);
        log.info("Gateway lifecycle: drain started ({} active inbound)", activeInbound.get());
        long deadline = System.nanoTime() + bound.toNanos();
        while (activeInbound.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        state.set(State.STOPPED);
        persist(State.STOPPED, null);
        log.info("Gateway lifecycle: drain finished, {} active inbound remained", activeInbound.get());
        return status();
    }

    /** Immediate stop: no wait for in-flight work (operators use drain first). */
    public synchronized Status stop() {
        state.set(State.STOPPED);
        persist(State.STOPPED, null);
        log.info("Gateway lifecycle: STOPPED ({} active inbound abandoned)", activeInbound.get());
        return status();
    }

    public synchronized Status fail(String error) {
        state.set(State.FAILED);
        lastError = error;
        persist(State.FAILED, error);
        return status();
    }

    /** Restart = drain with a short bound, then start. */
    public synchronized Status restart(Duration drainBound) {
        if (state.get() != State.STOPPED) {
            drain(drainBound);
        }
        return start();
    }

    public Status status() {
        return new Status(state.get(), activeInbound.get(), startedAt, lastError);
    }

    public record Status(State state, int activeInbound, Instant since, String lastError) {}

    private void persist(State newState, String error) {
        // The profile runtime row is the dashboard/health authority. Gateway
        // lifecycle has one JVM-wide adapter set today, so transitions are
        // recorded against the default profile until real per-profile adapter
        // processes exist; do not fabricate a separate worker.
        if (profileRuntimeRegistry != null) {
            profileRuntimeRegistry.updateWorkerState(
                GatewayHomeChannelService.DEFAULT_PROFILE,
                newState.name().toLowerCase(Locale.ROOT));
        }
        if (stateRepository == null) {
            return;
        }
        try {
            GatewayRuntimeStateEntity entity = stateRepository.findById(GatewayHomeChannelService.DEFAULT_PROFILE)
                .orElseGet(() -> {
                    GatewayRuntimeStateEntity created = new GatewayRuntimeStateEntity();
                    created.setProfile(GatewayHomeChannelService.DEFAULT_PROFILE);
                    return created;
                });
            entity.setState(newState.name().toLowerCase(Locale.ROOT));
            entity.setLastError(error);
            entity.setUpdatedAt(Instant.now());
            stateRepository.save(entity);
        } catch (Exception e) {
            // Persisted view is best-effort; runtime authority stays in memory.
            log.debug("Gateway runtime state persist failed: {}", e.getMessage());
        }
    }
}
