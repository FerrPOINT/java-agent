package com.azhukov.agent.core.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>Lock entries are removed after release when uncontended (same pattern as
 * the original AgentRuntimeService implementation) so the map does not grow
 * unboundedly.
 */
@Component
@Slf4j
public class SessionTurnLockManager {

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Acquire the turn lock for a session, waiting up to {@code timeoutSeconds}.
     *
     * @return true if acquired, false on timeout (session busy)
     */
    public boolean tryAcquire(UUID sessionId, long timeoutSeconds) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        try {
            return lock.tryLock(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Release the turn lock and remove the map entry when uncontended. */
    public void release(UUID sessionId) {
        ReentrantLock lock = locks.get(sessionId);
        if (lock == null) {
            return;
        }
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            // Not held by this thread — nothing to release.
            return;
        }
        // Remove the lock from the map to prevent unbounded growth. Only remove
        // when no other thread is waiting (tryLock succeeds immediately after
        // unlock, meaning no contention). Safe: a new turn on the same session
        // computeIfAbsent's a fresh lock if needed.
        try {
            if (lock.tryLock()) {
                try {
                    locks.remove(sessionId, lock);
                } finally {
                    lock.unlock();
                }
            }
        } catch (IllegalMonitorStateException ignored) {
            // raced with a waiter — leave the entry in place
        }
    }
}
