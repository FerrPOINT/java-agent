package com.azhukov.agent.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ChatResponse(
    String content,
    List<ToolCall> toolCalls
) {
    public ChatResponse {
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content != null ? content : "", Collections.emptyList());
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return new ChatResponse("", toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList());
    }

    /**
     * Creates a ChatResponse with BOTH text content AND tool calls.
     * <p>
     * This is used when the LLM returns visible text alongside tool calls —
     * the text is "commentary" (interim assistant message) that should be
     * shown to the user before tool execution begins.
     * Mirrors Hermes' {@code _emit_interim_assistant_message()} pattern.
     *
     * @param content   the visible text (commentary)
     * @param toolCalls the tool calls to execute
     * @return a ChatResponse with both content and toolCalls populated
     */
    public static ChatResponse textAndToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatResponse(
            content != null ? content : "",
            toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList()
        );
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Returns true if this response has non-empty visible text content.
     */
    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
