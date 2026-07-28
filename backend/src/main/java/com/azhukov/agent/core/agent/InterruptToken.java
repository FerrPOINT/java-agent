package com.azhukov.agent.core.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks cancellation flags per session so that long-running turns can be
 * interrupted cooperatively.
 */
@Component
public class InterruptToken {

    private final ConcurrentHashMap<UUID, AtomicBoolean> tokens = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if a cancel has been requested for the given session.
     */
    public boolean isCancelled(UUID sessionId) {
        AtomicBoolean flag = tokens.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * Marks the given session as cancelled.
     */
    public void cancel(UUID sessionId) {
        tokens.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(true);
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
}