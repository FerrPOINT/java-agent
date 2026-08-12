package com.azhukov.agent.core.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks cancellation flags per session so that long-running turns can be
 * interrupted cooperatively.  In addition to the simple boolean flag, this
 * component supports <em>cancellation callbacks</em> — Runnables that are
 * invoked when {@link #cancel(UUID)} is called, enabling mid-tool interruption
 * of long-running operations (e.g. shell commands in TerminalTool).
 */
@Component
public class InterruptToken {

    private final ConcurrentHashMap<UUID, AtomicBoolean> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Runnable> callbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicLong> timestamps = new ConcurrentHashMap<>();

    /** Default TTL for cleanup: 1 hour in milliseconds. */
    private static final long DEFAULT_TTL_MS = 3_600_000L;

    /**
     * ThreadLocal holding the current session ID for the streaming thread.
     * Set by {@link #setCurrentSessionId(UUID)} before a model stream starts,
     * cleared by {@link #clearCurrentSessionId()} after the stream ends.
     * This allows {@link LangChain4jModelClient} to check cancellation
     * without a direct reference to the InterruptToken instance.
     */
    private static final ThreadLocal<UUID> currentSessionId = new ThreadLocal<>();

    /**
     * Static reference to the singleton InterruptToken instance.
     * Set by the Spring container via {@link #setInstance(InterruptToken)}.
     */
    private static volatile InterruptToken instance;

    /**
     * Registers this instance as the singleton for static lookups
     * (e.g. from LangChain4jModelClient).
     */
    @jakarta.annotation.PostConstruct
    void init() {
        setInstance(this);
    }

    /**
     * Called by the container (or manually in tests) to register the singleton
     * so that {@link #isCancelledGlobally()} can check cancellation from code
     * that doesn't have a direct reference (e.g. LangChain4jModelClient).
     */
    public static void setInstance(InterruptToken token) {
        instance = token;
    }

    /**
     * Sets the session ID associated with the current thread's streaming context.
     * Call before starting a model stream; clear after the stream finishes.
     */
    public static void setCurrentSessionId(UUID sessionId) {
        currentSessionId.set(sessionId);
    }

    /**
     * Clears the session ID for the current thread.
     */
    public static void clearCurrentSessionId() {
        currentSessionId.remove();
    }

    /**
     * Returns the session ID associated with the current thread, or null if none.
     * Used by components that need session-scoped state (e.g. DefaultToolGuardrails).
     */
    public static UUID currentSessionId() {
        return currentSessionId.get();
    }

    /**
     * Checks whether the current thread's session has been cancelled,
     * using the static singleton instance.  Returns false if no session
     * is set on the current thread or no instance is registered.
     */
    public static boolean isCancelledGlobally() {
        UUID sessionId = currentSessionId.get();
        if (sessionId == null) return false;
        InterruptToken token = instance;
        return token != null && token.isCancelled(sessionId);
    }

    /**
     * Returns {@code true} if a cancel has been requested for the given session.
     */
    public boolean isCancelled(UUID sessionId) {
        if (sessionId == null) return false;
        AtomicBoolean flag = tokens.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * Marks the given session as cancelled and fires any registered
     * cancellation callback for that session.
     */
    public void cancel(UUID sessionId) {
        if (sessionId == null) return;
        tokens.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(true);
        timestamps.put(sessionId, new AtomicLong(System.currentTimeMillis()));
        Runnable cb = callbacks.get(sessionId);
        if (cb != null) {
            try {
                cb.run();
            } catch (Exception ignored) {
                // callback failures must not prevent cancellation
            }
        }
    }

    /**
     * Clears the cancellation flag for the given session so a new turn can proceed.
     */
    public void reset(UUID sessionId) {
        if (sessionId == null) return;
        AtomicBoolean flag = tokens.get(sessionId);
        if (flag != null) {
            flag.set(false);
        }
    }

    /**
     * Registers a cancellation callback for the given session.  When
     * {@link #cancel(UUID)} is called the callback is invoked, allowing
     * long-running operations to be killed mid-flight.
     *
     * @param sessionId the session to associate the callback with
     * @param callback  the action to run on cancellation (e.g. destroy a process)
     */
    public void registerCancellationCallback(UUID sessionId, Runnable callback) {
        if (sessionId != null && callback != null) {
            callbacks.put(sessionId, callback);
            timestamps.putIfAbsent(sessionId, new AtomicLong(System.currentTimeMillis()));
        }
    }

    /**
     * Removes any previously registered cancellation callback for the session.
     *
     * @param sessionId the session whose callback should be removed
     */
    public void unregister(UUID sessionId) {
        if (sessionId != null) {
            callbacks.remove(sessionId);
        }
    }

    /**
     * Removes all state (flag, callback, timestamp) for the given session.
     * Call after stream completion to free the map entries.
     *
     * @param sessionId the session to remove
     */
    public void remove(UUID sessionId) {
        if (sessionId == null) return;
        tokens.remove(sessionId);
        callbacks.remove(sessionId);
        timestamps.remove(sessionId);
    }

    /**
     * Removes entries older than the default TTL (1 hour).
     * Can be called periodically to avoid memory leaks from abandoned sessions.
     */
    public void cleanup() {
        cleanup(DEFAULT_TTL_MS);
    }

    /**
     * Removes entries older than the given TTL in milliseconds.
     * Can be called periodically to avoid memory leaks from abandoned sessions.
     *
     * @param ttlMs time-to-live in milliseconds; entries older than this are removed
     */
    public void cleanup(long ttlMs) {
        long now = System.currentTimeMillis();
        timestamps.entrySet().removeIf(entry -> {
            long age = now - entry.getValue().get();
            if (age > ttlMs) {
                UUID sessionId = entry.getKey();
                tokens.remove(sessionId);
                callbacks.remove(sessionId);
                return true;
            }
            return false;
        });
    }
}