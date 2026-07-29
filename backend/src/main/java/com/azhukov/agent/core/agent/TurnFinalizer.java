package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Hook invoked at the end of every turn (success or failure) to perform cleanup
 * operations: evict prompt cache on failure, update session timestamp, log turn metrics.
 * <p>
 * Message persistence is handled by {@code AgentRuntimeService} — the finalizer
 * does NOT duplicate that work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TurnFinalizer {

    private final PromptCacheTracker promptCacheTracker;

    /**
     * Finalize a turn.
     *
     * @param sessionId  the session UUID
     * @param messages   all messages produced during the turn (system + user + assistant + tool results)
     * @param success    whether the turn completed successfully
     */
    public void finalize(UUID sessionId, List<Message> messages, boolean success) {
        int msgCount = messages != null ? messages.size() : 0;

        if (!success) {
            // Evict prompt cache so the next turn rebuilds context from scratch
            promptCacheTracker.invalidate(sessionId.toString());
            log.debug("Turn FAILED for session {}: {} messages, prompt cache evicted", sessionId, msgCount);
        } else {
            log.debug("Turn completed for session {}: {} messages, prompt cache preserved", sessionId, msgCount);
        }
    }
}