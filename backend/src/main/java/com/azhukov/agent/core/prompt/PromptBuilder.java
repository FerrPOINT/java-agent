package com.azhukov.agent.core.prompt;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import java.util.List;

public interface PromptBuilder {

    Message buildSystemMessage(Session session);

    default Message buildSystemMessage(Session session, String systemMessageOverride) {
        Message base = buildSystemMessage(session);
        if (systemMessageOverride == null || systemMessageOverride.isBlank()) {
            return base;
        }
        String content = (base.content() == null || base.content().isBlank())
            ? systemMessageOverride
            : base.content() + "\n\n" + systemMessageOverride;
        return Message.withContent(base, content);
    }

    default List<Message> prependSystem(List<Message> messages, Session session) {
        if (messages.isEmpty() || messages.get(0).role() == com.azhukov.agent.core.model.Role.SYSTEM
                || messages.get(0).role() == com.azhukov.agent.core.model.Role.DEVELOPER) {
            return messages;
        }
        List<Message> result = new java.util.ArrayList<>(messages.size() + 1);
        result.add(buildSystemMessage(session));
        result.addAll(messages);
        return result;
    }
}
