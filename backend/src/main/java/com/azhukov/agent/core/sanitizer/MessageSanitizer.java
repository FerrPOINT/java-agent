package com.azhukov.agent.core.sanitizer;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces a valid role alternation for chat-model APIs.
 *
 * Allowed sequences:
 *   system (optional, first only)
 *   user
 *   assistant
 *   (tool)*
 *   assistant
 *   (user ...)
 *
 * Collapses consecutive same-role messages (except tool results which are kept grouped).
 * Rejects messages with no user message.
 */
public interface MessageSanitizer {

    List<Message> sanitize(List<Message> messages);

    static MessageSanitizer defaults() {
        return new DefaultMessageSanitizer();
    }
}
