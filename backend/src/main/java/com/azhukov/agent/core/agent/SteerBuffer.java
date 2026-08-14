package com.azhukov.agent.core.agent;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stores mid-run steer notes per session.
 * The steer text is injected into the next tool result's content,
 * giving the agent new context without breaking the tool-calling loop.
 * <p>
 * Multiple steers for the same session are concatenated with {@code \n}
 * so the agent sees them as a single block, mirroring Hermes
 * {@code _pending_steer} accumulation in {@code run_agent.py}.
 */
@Component
public class SteerBuffer {

    private final ConcurrentMap<UUID, String> pendingSteers = new ConcurrentHashMap<>();

    /**
     * Inject a steer note for the given session.
     * If a steer is already pending, the new text is appended with a
     * {@code \n} separator so multiple steers accumulate into one block.
     *
     * @param sessionId the session UUID
     * @param text      the steer text to inject
     * @return true if the steer was accepted
     */
    public boolean steer(UUID sessionId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        pendingSteers.merge(sessionId, text, (existing, newText) -> existing + "\n" + newText);
        return true;
    }

    /**
     * Consume and return the pending steer text for the given session, if any.
     * The steer is removed after consumption so it's only injected once.
     *
     * @param sessionId the session UUID
     * @return the steer text, or null if none pending
     */
    public String consume(UUID sessionId) {
        return pendingSteers.remove(sessionId);
    }

    /**
     * Check if a steer is pending for the given session.
     */
    public boolean hasPending(UUID sessionId) {
        return pendingSteers.containsKey(sessionId);
    }

    /**
     * Clear any pending steer for the given session.
     */
    public void clear(UUID sessionId) {
        pendingSteers.remove(sessionId);
    }
}