package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ToolCall;

import java.util.List;

public interface StreamingResponseHandler {

    void onToken(String token);

    default void onToolCalls(List<ToolCall> toolCalls) {}

    void onComplete();

    /** Called when the model completes with a finish reason (STOP/LENGTH/CONTENT_FILTER/TOOL_EXECUTION/OTHER).
     * Default implementation delegates to onComplete() for backward compatibility.
     * Override to handle finish_reason-specific routing (truncation, content filter, etc.). */
    default void onComplete(String finishReason) {
        onComplete();
    }

    /**
     * Completion carrying the streamed token usage. Lets the empty-response
     * deterministic guard see outputTokens (Hermes empty_response_guard.py:
     * two consecutive zero-output attempts from one model/provider/finish
     * signature are deterministic — stop paying for retries). Default drops
     * the usage and delegates, so existing handlers stay source-compatible.
     */
    default void onComplete(String finishReason, Long outputTokens) {
        onComplete(finishReason);
    }

    void onError(Throwable error);
}
