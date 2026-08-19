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

    void onError(Throwable error);
}
