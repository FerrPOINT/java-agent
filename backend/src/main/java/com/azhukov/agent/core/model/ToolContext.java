package com.azhukov.agent.core.model;

import com.azhukov.agent.config.AgentProperties;
import java.util.Objects;

public record ToolContext(
    Session session,
    AgentProperties properties
) {
    public ToolContext {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
    }
}
