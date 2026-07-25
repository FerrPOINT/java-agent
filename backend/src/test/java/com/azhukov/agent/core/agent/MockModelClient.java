package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable mock model client for tests. Supports a scripted sequence of responses.
 */
public class MockModelClient implements ModelClient {

    private final List<ChatResponse> responses;
    private int calls = 0;

    public MockModelClient(List<ChatResponse> responses) {
        this.responses = new ArrayList<>(responses);
    }

    public MockModelClient(String finalText) {
        this.responses = List.of(ChatResponse.text(finalText));
    }

    /**
     * Convenience constructor for the common tool-call-then-final-text scenario.
     */
    public MockModelClient(List<ToolCall> firstCalls, String finalText) {
        List<ChatResponse> list = new ArrayList<>();
        for (ToolCall tc : firstCalls) {
            list.add(ChatResponse.toolCalls(List.of(tc)));
        }
        list.add(ChatResponse.text(finalText));
        this.responses = list;
    }

    public MockModelClient(ToolCall... toolCalls) {
        List<ChatResponse> list = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            list.add(ChatResponse.toolCalls(List.of(tc)));
        }
        this.responses = list;
    }

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        if (calls >= responses.size()) {
            return ChatResponse.text("(no more scripted responses)");
        }
        return responses.get(calls++);
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return "mock-vision";
    }

    public int calls() {
        return calls;
    }
}
