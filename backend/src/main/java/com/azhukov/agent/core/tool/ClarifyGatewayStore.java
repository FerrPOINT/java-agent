package com.azhukov.agent.core.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hermes parity (tools/clarify_gateway.py): blocking clarify primitive. The
 * agent turn runs on a worker thread while the platform adapter renders the
 * prompt; a pending entry is stored here and the tool blocks on its future
 * until a callback (inline button) or a typed text reply resolves it, or the
 * timeout fires. Typed replies are coerced by the caller (label/number
 * matching, multi-select parsing) before {@link #resolve}.
 */
@Slf4j
@Component
public class ClarifyGatewayStore {

    /** One pending clarify request. */
    public record PendingClarify(
        String clarifyId,
        String sessionKey,
        String question,
        List<String> choices,
        boolean multiSelect,
        long registrationSequence,
        CompletableFuture<String> future
    ) {
        boolean isAwaitingText() {
            return choices == null || choices.isEmpty();
        }
    }

    /** Outcome of a typed-reply resolution attempt. */
    public enum TextOutcome { RESOLVED, REJECTED_PROSE, REJECTED_SELECTION, NO_PENDING }

    public static final long DEFAULT_TIMEOUT_SECONDS = 3600;

    private final Map<String, PendingClarify> entries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PendingClarify>> sessionIndex = new ConcurrentHashMap<>();
    private final AtomicLong registrationSequence = new AtomicLong();

    /** Register a pending clarify; the caller then blocks on the entry's future. */
    public PendingClarify register(String sessionKey, String question, List<String> choices, boolean multiSelect) {
        String id = UUID.randomUUID().toString();
        PendingClarify entry = new PendingClarify(
            id, sessionKey, question,
            choices == null ? List.of() : List.copyOf(choices),
            multiSelect && choices != null && !choices.isEmpty(),
            registrationSequence.incrementAndGet(),
            new CompletableFuture<>());
        entries.put(id, entry);
        sessionIndex.computeIfAbsent(sessionKey, k -> new ConcurrentHashMap<>()).put(id, entry);
        return entry;
    }

    /**
     * Block until the entry resolves or the timeout elapses; null on timeout.
     * Polls in 1s slices (like the Hermes event loop) so a shutdown/interrupt
     * can cancel the wait promptly.
     */
    public String awaitResponse(PendingClarify entry, long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
            timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                String done = entry.future().get(1, TimeUnit.SECONDS);
                cleanup(entry);
                return done;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cleanup(entry);
                return null;
            } catch (java.util.concurrent.TimeoutException te) {
                // slice elapsed — keep waiting until deadline
            } catch (java.util.concurrent.ExecutionException ee) {
                cleanup(entry);
                return null;
            }
        }
        cleanup(entry);
        return null;
    }

    /** Unblock the waiter; false if already resolved/expired/unknown. */
    public boolean resolve(String clarifyId, String response) {
        PendingClarify entry = entries.get(clarifyId);
        if (entry == null || entry.future().isDone()) {
            return false;
        }
        boolean accepted = entry.future().complete(response == null ? "" : response);
        if (accepted) {
            cleanup(entry);
        }
        return accepted;
    }

    /** Oldest pending entry for the session (any kind) — typed replies resolve it rather than queue a new turn. */
    public PendingClarify pendingForSession(String sessionKey) {
        Map<String, PendingClarify> index = sessionIndex.get(sessionKey);
        if (index == null) {
            return null;
        }
        return index.values().stream()
            .min(java.util.Comparator.comparingLong(PendingClarify::registrationSequence))
            .orElse(null);
    }

    /** True only when the pending clarify id belongs to the requested session. */
    public boolean belongsToSession(String sessionKey, String clarifyId) {
        PendingClarify entry = entries.get(clarifyId);
        return entry != null && entry.sessionKey().equals(sessionKey);
    }

    /** Drop every pending entry for a session (/new, interrupt, shutdown). */
    public int clearSession(String sessionKey) {
        Map<String, PendingClarify> index = sessionIndex.remove(sessionKey);
        if (index == null) {
            return 0;
        }
        int cancelled = 0;
        for (PendingClarify entry : index.values()) {
            if (entries.remove(entry.clarifyId()) != null) {
                entry.future().complete("");
                cancelled++;
            }
        }
        return cancelled;
    }

    private void cleanup(PendingClarify entry) {
        entries.remove(entry.clarifyId());
        Map<String, PendingClarify> index = sessionIndex.get(entry.sessionKey());
        if (index != null) {
            index.remove(entry.clarifyId());
            if (index.isEmpty()) {
                sessionIndex.remove(entry.sessionKey(), index);
            }
        }
    }
}
