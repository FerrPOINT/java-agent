package com.azhukov.agent.core.model;

import java.util.Objects;

public record ToolResult(
    boolean success,
    String content,
    String error
) {
    public ToolResult {
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, "", error);
    }
}
