package com.azhukov.agent.core.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record TurnResult(
    List<Message> messages,
    boolean completed,
    String error
) {
    public TurnResult {
        Objects.requireNonNull(messages, "messages");
    }

    public static TurnResult error(String error) {
        return new TurnResult(List.of(), false, error);
    }

    public String finalText() {
        if (error != null && !error.isEmpty()) {
            return "Error: " + error;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.role() == Role.ASSISTANT || m.role() == Role.TOOL) {
                return m.content();
            }
        }
        return "";
    }
}
