package com.azhukov.agent.client;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import java.util.List;

public class NoOpModelClient implements ModelClient {

    @Override
    public ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        if (!tools.isEmpty()) {
            return ChatResponse.toolCalls(List.of(
                new ToolCall("call-1", "read_file", "{\"path\":\"/opt/dev/java-agent/README.md\",\"offset\":1,\"limit\":5}")
            ));
        }
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return ChatResponse.text("NoOp response: " + last);
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        return "NoOp vision: image length=" + (base64Image != null ? base64Image.length() : 0) + ", prompt=" + prompt;
    }
}
