package com.azhukov.agent.core.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        long sequence,
        String question,
        List<String> choices,
        boolean multiSelect,
        CompletableFuture<String> future,
        boolean acceptsCustomResponse
    ) {
        boolean isAwaitingText() {
            return choices == null || choices.isEmpty() || acceptsCustomResponse;
        }
    }

    /** Outcome of a typed-reply resolution attempt. */
    public enum TextOutcome { RESOLVED, REJECTED_PROSE, REJECTED_SELECTION, NO_PENDING }

    public static final long DEFAULT_TIMEOUT_SECONDS = 3600;

    /** Monotonic order makes typed answers bind to the first visible batch question. */
    private final java.util.concurrent.atomic.AtomicLong nextSequence = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, PendingClarify> entries = new ConcurrentHashMap<>();
    private final Map<String, Map<String, PendingClarify>> sessionIndex = new ConcurrentHashMap<>();

    /** Register a pending clarify; the caller then blocks on the entry's future. */
    public PendingClarify register(String sessionKey, String question, List<String> choices, boolean multiSelect) {
        return register(sessionKey, question, choices, multiSelect, choices == null || choices.isEmpty());
    }

    /**
     * Register a pending clarify and declare whether a free-form answer is valid.
     * Choice prompts require this explicit bit because their text route otherwise
     * cannot distinguish ordinary prose from a deliberate "Other" response.
     */
    public PendingClarify register(String sessionKey, String question, List<String> choices, boolean multiSelect,
                                   boolean acceptsCustomResponse) {
        String id = UUID.randomUUID().toString();
        PendingClarify entry = new PendingClarify(
            id, sessionKey, nextSequence.incrementAndGet(), question,
            choices == null ? List.of() : List.copyOf(choices),
            multiSelect && choices != null && !choices.isEmpty(),
            new CompletableFuture<>(),
            acceptsCustomResponse);
        entries.put(id, entry);
        sessionIndex.computeIfAbsent(sessionKey, k -> new ConcurrentHashMap<>()).put(id, entry);
        log.info("clarify_registered session={} id={} sequence={} choices={} multiSelect={}",
            sessionKey, id, entry.sequence(), entry.choices().size(), entry.multiSelect());
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
        return resolveForSession(null, clarifyId, response);
    }

    /**
     * Unblock only an entry owned by {@code sessionKey}. The public adapter
     * endpoints must use this overload: a clarify ID is not authorization.
     */
    public boolean resolveForSession(String sessionKey, String clarifyId, String response) {
        PendingClarify entry = entries.get(clarifyId);
        if (entry == null || entry.future().isDone()
            || (sessionKey != null && !entry.sessionKey().equals(sessionKey))) {
            log.info("clarify_resolve_rejected id={} reason={}", clarifyId,
                entry == null ? "unknown" : entry.future().isDone() ? "already_completed" : "wrong_session");
            return false;
        }
        boolean accepted = entry.future().complete(response == null ? "" : response);
        if (accepted) {
            log.info("clarify_resolved session={} id={} responseChars={}",
                entry.sessionKey(), clarifyId, response == null ? 0 : response.length());
            cleanup(entry);
        }
        return accepted;
    }

    /**
     * Permit a free-form response after the adapter receives the explicit
     * Other action. The entry is atomically replaced so later text coercion
     * accepts prose without changing its waiter or registration order.
     */
    public boolean armCustomResponse(String sessionKey, String clarifyId) {
        PendingClarify entry = entries.get(clarifyId);
        if (entry == null || entry.future().isDone() || !entry.sessionKey().equals(sessionKey)) {
            return false;
        }
        if (entry.acceptsCustomResponse()) {
            return true;
        }
        PendingClarify armed = new PendingClarify(entry.clarifyId(), entry.sessionKey(), entry.sequence(),
            entry.question(), entry.choices(), entry.multiSelect(), entry.future(), true);
        if (!entries.replace(clarifyId, entry, armed)) {
            return false;
        }
        Map<String, PendingClarify> index = sessionIndex.get(sessionKey);
        if (index != null) {
            index.replace(clarifyId, entry, armed);
        }
        log.info("clarify_custom_response_armed session={} id={}", sessionKey, clarifyId);
        return true;
    }

    /** Oldest pending entry for the session, matching the order prompts were rendered. */
    public PendingClarify pendingForSession(String sessionKey) {
        Map<String, PendingClarify> index = sessionIndex.get(sessionKey);
        if (index == null) {
            return null;
        }
        // Insertion-ordered scan is not guaranteed by ConcurrentHashMap; pick the
        // earliest-created entry (UUIDs carry no order, compare by registration
        // sequence via the future's internal ordering — fall back to any entry:
        // sessions almost always have exactly one pending clarify).
        return index.values().stream()
            .min(java.util.Comparator.comparingLong(PendingClarify::sequence))
            .map(entry -> {
                log.debug("clarify_text_target session={} id={} sequence={}",
                    sessionKey, entry.clarifyId(), entry.sequence());
                return entry;
            })
            .orElse(null);
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
