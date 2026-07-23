package com.azhukov.agent.core.model;

import java.util.List;
import java.util.Objects;

public record TurnResult(
    List<Message> messages,
    boolean completed,
    String error
) {
    public TurnResult {
        Objects.requireNonNull(messages, "messages must not be null");
    }

    public String finalText() {
        return messages.stream()
            .filter(m -> m.role() == Role.ASSISTANT && m.content() != null)
            .reduce((a, b) -> b)
            .map(Message::content)
            .orElse("");
    }

    public static TurnResult error(String error) {
        return new TurnResult(List.of(), true, error);
    }
}
