package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import java.util.List;

public interface ContextEngine {

    List<Message> prepareContext(Session session, List<Message> messages);

    /**
     * Preflight check: estimate whether compression should be triggered before the model API call.
     * Returns true if estimated tokens exceed 80% of maxTokens.
     * Default is false (no preflight compression).
     */
    default boolean shouldCompressPreflight(List<Message> messages) {
        return false;
    }
}