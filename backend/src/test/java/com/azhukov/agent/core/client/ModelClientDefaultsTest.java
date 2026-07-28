package com.azhukov.agent.core.client;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ModelClientDefaultsTest {

    @Test
    void streamDefaultFallsBackToComplete() {
        List<String> tokens = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> completes = new ArrayList<>();

        ModelClient client = new ModelClient() {
            @Override
            public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
                return ChatResponse.text("hi");
            }
        };

        client.stream(List.of(), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onToolCalls(List<ToolCall> toolCalls) { calls.addAll(toolCalls); }
            @Override public void onComplete() { completes.add("ok"); }
            @Override public void onError(Throwable error) { errors.add(error.getMessage()); }
        });

        assertThat(tokens).containsExactly("hi");
        assertThat(completes).containsExactly("ok");
        assertThat(errors).isEmpty();
    }

    @Test
    void analyzeImageAsyncDefaultUsesBlockingMethod() {
        ModelClient client = new ModelClient() {
            @Override
            public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
                return ChatResponse.text("");
            }

            @Override
            public String analyzeImage(String base64Image, String prompt) {
                return "seen";
            }
        };
        CompletableFuture<String> f = client.analyzeImageAsync("img", "prompt");
        assertThat(f).isCompletedWithValue("seen");
    }

    @Test
    void defaultAnalyzeImageReturnsUnsupportedText() {
        ModelClient client = new ModelClient() {
            @Override
            public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
                return ChatResponse.text("");
            }
        };
        assertThat(client.analyzeImage("img", "prompt"))
            .isEqualTo("Vision analysis is not supported by this model client.");
    }
}
