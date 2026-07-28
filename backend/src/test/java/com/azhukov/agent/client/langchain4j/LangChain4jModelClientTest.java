package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LangChain4jModelClientTest {

    private final AgentProperties properties = new AgentProperties();
    private final ChatModel chatModel = mock(ChatModel.class);
    private final StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
    private final AtomicReference<LangChain4jModelClient.Usage> usage = new AtomicReference<>();

    private LangChain4jModelClient client() {
        return new LangChain4jModelClient(chatModel, streamingChatModel, properties, usage::set, null, null);
    }

    @Test
    void completeReturnsText() {
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        dev.langchain4j.model.chat.response.ChatResponse response = dev.langchain4j.model.chat.response.ChatResponse.builder()
            .aiMessage(AiMessage.from("hello"))
            .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        ChatResponse r = client.complete(List.of(Message.user("hi")), List.of());
        assertThat(r.content()).isEqualTo("hello");
        assertThat(r.hasToolCalls()).isFalse();
    }

    @Test
    void completeReturnsToolCalls() {
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        ToolExecutionRequest req = ToolExecutionRequest.builder().id("1").name("search").arguments("{\"q\":\"x\"}").build();
        dev.langchain4j.model.chat.response.ChatResponse response = dev.langchain4j.model.chat.response.ChatResponse.builder()
            .aiMessage(AiMessage.from(List.of(req)))
            .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        ChatResponse r = client.complete(List.of(Message.user("search x")), List.of());
        assertThat(r.hasToolCalls()).isTrue();
        assertThat(r.toolCalls()).containsExactly(new ToolCall("1", "search", "{\"q\":\"x\"}"));
    }

    @Test
    void analyzeImageReturnsText() {
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        dev.langchain4j.model.chat.response.ChatResponse response = dev.langchain4j.model.chat.response.ChatResponse.builder()
            .aiMessage(AiMessage.from("an image"))
            .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        String r = client.analyzeImage("data:image/png;base64,abc", "describe");
        assertThat(r).isEqualTo("an image");
    }

    @Test
    void toolSpecificationBuiltFromDefinition() {
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        ToolDefinition def = new ToolDefinition("t", "desc", Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("description", "n")),
            "required", List.of("name")
        ));

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        client.complete(List.of(Message.user("go")), List.of(def));
        verify(chatModel).chat(argThat((ChatRequest req) ->
            req.toolSpecifications().stream()
                .anyMatch(s -> s.name().equals("t") && s.parameters().required().contains("name"))
        ));
    }
}
