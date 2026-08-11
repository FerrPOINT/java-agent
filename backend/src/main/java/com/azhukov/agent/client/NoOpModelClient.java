package com.azhukov.agent.client;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;

public class NoOpModelClient implements ModelClient {

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options) {
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return ChatResponse.text("NoOp response: " + last);
    }

    @Override
    public void stream(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options,
                       StreamingResponseHandler handler) {
        ChatResponse response = complete(messages, tools, options);
        if (response.hasToolCalls()) {
            handler.onToolCalls(response.toolCalls());
        } else {
            handler.onToken(response.content());
        }
        handler.onComplete();
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return "NoOp vision: image length=" + (base64Image != null ? base64Image.length() : 0) + ", prompt=" + prompt;
    }

    @Override
    public String getModelName() {
        return "noop";
    }
}
