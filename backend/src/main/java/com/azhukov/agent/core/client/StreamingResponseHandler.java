package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ToolCall;

import java.util.List;

public interface StreamingResponseHandler {

    void onToken(String token);

    default void onToolCalls(List<ToolCall> toolCalls) {}

    void onComplete();

    void onError(Throwable error);
}
