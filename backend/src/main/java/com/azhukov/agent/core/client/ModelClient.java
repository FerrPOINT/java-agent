package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModelClient {

    default ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        return complete(messages, tools, ModelRequestOptions.empty());
    }

    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options);

    default void stream(List<Message> messages, List<ToolDefinition> tools, StreamingResponseHandler handler) {
        stream(messages, tools, ModelRequestOptions.empty(), handler);
    }

    default void stream(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options,
                        StreamingResponseHandler handler) {
        try {
            ChatResponse response = complete(messages, tools, options);
            if (response.hasToolCalls()) {
                handler.onToolCalls(response.toolCalls());
            } else {
                handler.onToken(response.content());
            }
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    default CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt) {
        return CompletableFuture.completedFuture(analyzeImage(base64Image, prompt));
    }

    default String analyzeImage(String base64Image, String prompt) {
        return "Vision analysis is not supported by this model client.";
    }
}
