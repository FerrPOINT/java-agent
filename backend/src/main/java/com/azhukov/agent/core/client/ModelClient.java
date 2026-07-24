package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModelClient {

    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools);

    default CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt) {
        return CompletableFuture.completedFuture(analyzeImage(base64Image, prompt));
    }

    default String analyzeImage(String base64Image, String prompt) {
        return "Vision analysis is not supported by this model client.";
    }
}
