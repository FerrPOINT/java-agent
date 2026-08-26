package com.azhukov.agent.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A model turn result: visible content, tool calls and the provider-reported
 * finish reason.
 *
 * <p>{@code finishReason} carries the wire-level termination signal
 * ("LENGTH", "TOOL_EXECUTION", "CONTENT_FILTER", "STOP", …) when the client
 * can extract it (streaming always; sync via langchain4j
 * {@code ChatResponse.finishReason()}). It drives the shared recovery
 * policies (LENGTH continuation, dropped-toolcall re-prompt) in BOTH runtimes
 * — a missing reason is treated as {@code "STOP"} and disables recovery,
 * matching the pre-recovery behaviour.</p>
 */
public record ChatResponse(
    String content,
    List<ToolCall> toolCalls,
    String finishReason,
    TokenUsage usage
) {
    public ChatResponse {
        Objects.requireNonNull(content, "content must not be null");
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList();
        finishReason = finishReason != null ? finishReason : "STOP";
    }

    /** Canonical 3-arg constructor: no provider usage (recovery paths fail open). */
    public ChatResponse(String content, List<ToolCall> toolCalls, String finishReason) {
        this(content, toolCalls, finishReason, null);
    }

    /** Legacy 2-arg constructor: tool-call response with default finish reason. */
    public ChatResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, "TOOL_EXECUTION", null);
    }

    /** Returns a copy carrying the provider-reported usage, or this when usage is null. */
    public ChatResponse withUsage(TokenUsage usage) {
        return usage == null ? this : new ChatResponse(content, toolCalls, finishReason, usage);
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content != null ? content : "", Collections.emptyList(), "STOP");
    }

    public static ChatResponse text(String content, String finishReason) {
        return new ChatResponse(content != null ? content : "", Collections.emptyList(), finishReason);
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls) {
        return new ChatResponse("", toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList(), "TOOL_EXECUTION");
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
            toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList(),
            "TOOL_EXECUTION"
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
