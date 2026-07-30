package com.azhukov.agent.core.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Returns {@code true} if a cancel has been requested for the given session.
     */
    public boolean isCancelled(UUID sessionId) {
        AtomicBoolean flag = tokens.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * Marks the given session as cancelled and fires any registered
     * cancellation callback for that session.
     */
    public void cancel(UUID sessionId) {
        tokens.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(true);
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
}