package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LangChain4jModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jModelClient.class);

    private final ChatModel chatModel;
    private final AgentProperties properties;

    public LangChain4jModelClient(AgentProperties properties) {
        this.properties = properties;
        this.chatModel = dev.langchain4j.model.openai.OpenAiChatModel.builder()
            .baseUrl(properties.getModel().getBaseUrl())
            .apiKey(properties.getModel().getApiKey())
            .modelName(properties.getModel().getModelName())
            .timeout(Duration.ofSeconds(properties.getModel().getTimeoutSeconds()))
            .maxRetries(properties.getModel().getMaxRetries())
            .temperature(properties.getModel().getTemperature())
            .build();
    }

    @Override
    @Retry(name = "model")
    @TimeLimiter(name = "model")
    public com.azhukov.agent.core.model.ChatResponse complete(List<Message> messages, List<ToolDefinition> tools) {
        List<ChatMessage> chatMessages = messages.stream()
            .map(this::toLangChainMessage)
            .collect(Collectors.toList());

        List<ToolSpecification> specs = tools.stream()
            .map(this::toToolSpecification)
            .collect(Collectors.toList());

        ChatRequest request = ChatRequest.builder()
            .messages(chatMessages)
            .toolSpecifications(specs)
            .build();

        log.debug("Sending {} messages to model {}", chatMessages.size(), request);
        dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
        AiMessage aiMessage = response.aiMessage();

        if (aiMessage.hasToolExecutionRequests()) {
            List<ToolCall> calls = aiMessage.toolExecutionRequests().stream()
                .map(r -> new ToolCall(r.id(), r.name(), r.arguments()))
                .collect(Collectors.toList());
            return com.azhukov.agent.core.model.ChatResponse.toolCalls(calls);
        }

        return com.azhukov.agent.core.model.ChatResponse.text(aiMessage.text());
    }

    @Override
    @Retry(name = "model")
    @TimeLimiter(name = "model")
    public CompletableFuture<String> analyzeImageAsync(String base64Image, String prompt) {
        return CompletableFuture.supplyAsync(() -> analyzeImage(base64Image, prompt));
    }

    @Override
    public String analyzeImage(String base64Image, String prompt) {
        UserMessage message = UserMessage.from(
            TextContent.from(prompt),
            ImageContent.from(base64Image, "image/png")
        );
        ChatRequest request = ChatRequest.builder()
            .messages(List.of(message))
            .build();
        dev.langchain4j.model.chat.response.ChatResponse response = chatModel.chat(request);
        return response.aiMessage().text();
    }

    private ChatMessage toLangChainMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    var requests = message.toolCalls().stream()
                        .map(c -> ToolExecutionRequest.builder()
                            .id(c.id())
                            .name(c.name())
                            .arguments(c.arguments())
                            .build())
                        .collect(Collectors.toList());
                    yield AiMessage.from(requests);
                }
                yield AiMessage.from(message.content());
            }
            case TOOL -> ToolExecutionResultMessage.from(message.toolCallId(), null, message.content());
        };
    }

    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        return ToolSpecification.builder()
            .name(definition.name())
            .description(definition.description())
            .parameters(toJsonSchema(definition.parameters()))
            .build();
    }

    private JsonObjectSchema toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return JsonObjectSchema.builder().build();
        }
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                builder.addStringProperty(e.getKey(), descriptionOf(e.getValue()));
            }
        }
        if (required != null) {
            builder.required(required);
        }
        return builder.build();
    }

    private String descriptionOf(Object spec) {
        if (spec instanceof Map m) {
            Object desc = m.get("description");
            return desc != null ? desc.toString() : "";
        }
        return "";
    }
}
