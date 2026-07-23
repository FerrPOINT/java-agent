package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;

class MockModelClient implements ModelClient {

    private final List<ToolCall> firstCalls;
    private final String finalText;
    private int calls = 0;

    MockModelClient(List<ToolCall> firstCalls, String finalText) {
        this.firstCalls = firstCalls;
        this.finalText = finalText;
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        if (!tools.isEmpty() && calls < firstCalls.size()) {
            calls++;
            return ChatResponse.toolCalls(List.of(firstCalls.get(calls - 1)));
        }
        return ChatResponse.text(finalText);
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return "mock-vision";
    }
}
