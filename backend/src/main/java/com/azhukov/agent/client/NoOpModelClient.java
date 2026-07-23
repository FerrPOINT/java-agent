package com.azhukov.agent.client;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;

public class NoOpModelClient implements ModelClient {

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return ChatResponse.text("NoOp response: " + last);
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return "NoOp vision: image length=" + (base64Image != null ? base64Image.length() : 0) + ", prompt=" + prompt;
    }
}
