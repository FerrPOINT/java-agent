package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Hook invoked at the end of every turn (success or failure) to allow cleanup
 * operations such as persisting messages, updating session timestamps,
 * evicting caches, or notifying external hooks.
 * <p>
 * Currently only logs; structured for future enhancement.
 */
@Component
@Slf4j
public class TurnFinalizer {

    public void finalize(UUID sessionId, List<Message> messages, boolean success) {
        log.debug("Finalizing turn for session {}: {} messages, success={}",
            sessionId, messages != null ? messages.size() : 0, success);
        // Future enhancements: persist messages, update session timestamp,
        // evict cache, notify hooks, etc.
    }
}