package com.azhukov.agent.core.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared per-session turn lock — mutual exclusion between the SYNC path
 * (AgentRuntimeService.chat) and the STREAMING path
 * (AgentStreamingService.streamTurn).
 *
 * <p>Previously only the sync path locked (live-e2e 2026-09-01, commit 31dec90):
 * two parallel streaming POSTs on the same sessionId ran two agent loops
 * concurrently — both read the same contextEngine snapshot, both wrote to the
 * DB, and the responses interleaved (live-verified: stream 2 returned
 * "CONCURRENT_A\nCONCURRENT_B"). A sync turn and a streaming turn on the same
 * session could also interleave for the same reason.
 *
 * <p>Each acquisition attempt retains the entry until it fails or its acquired
 * hold is released. Waiting and nested turns therefore cannot lose the lock
 * identity used for steer admission; idle entries are still removed.
 */
@Component
@Slf4j
public class SessionTurnLockManager {

    private final Map<UUID, TurnLock> locks = new ConcurrentHashMap<>();

    private static final class TurnLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    /**
     * Acquire the turn lock for a session, waiting up to {@code timeoutSeconds}.
     *
     * @return true if acquired, false on timeout (session busy)
     */
    public boolean tryAcquire(UUID sessionId, long timeoutSeconds) {
        TurnLock entry = locks.compute(sessionId, (id, current) -> {
            TurnLock retained = current != null ? current : new TurnLock();
            retained.references++;
            return retained;
        });
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (!acquired) releaseReference(sessionId, entry);
        }
    }

    /**
     * Execute an action only while this session's active turn still owns its
     * lock. The monitor makes the check and enqueue atomic with release, so a
     * steer cannot be accepted after that turn has stopped owning the lock.
     */
    public boolean runIfLocked(UUID sessionId, BooleanSupplier action) {
        TurnLock entry = locks.get(sessionId);
        if (entry == null) {
            return false;
        }
        synchronized (entry.lock) {
            return entry.lock.isLocked() && action.getAsBoolean();
        }
    }

    /** Release one owned hold, retaining the identity for any other hold or waiter. */
    public void release(UUID sessionId) {
        TurnLock entry = locks.get(sessionId);
        if (entry == null) {
            return;
        }
        synchronized (entry.lock) {
            if (!entry.lock.isHeldByCurrentThread()) return;
            entry.lock.unlock();
            releaseReference(sessionId, entry);
        }
    }

    private void releaseReference(UUID sessionId, TurnLock expected) {
        locks.computeIfPresent(sessionId, (id, current) -> {
            if (current != expected) return current;
            current.references--;
            return current.references == 0 ? null : current;
        });
    }
}
