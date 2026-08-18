package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse.Builder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackModelClientTest {

    @Mock
    private ChatModel chatModel;

    /**
     * Helper: intercept the static OpenAiChatModel.builder() chain to inject a mock ChatModel.
     * The FallbackModelClient constructor calls builder().baseUrl().apiKey().modelName()
     * .timeout().maxRetries().temperature().build() — we mock all builder methods to return
     * the mock builder, then return our mock ChatModel from build().
     */
    @SuppressWarnings("unchecked")
    private FallbackModelClient createClientWithMockedModel() {
        OpenAiChatModel.OpenAiChatModelBuilder mockBuilder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
        when(mockBuilder.baseUrl(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.apiKey(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.modelName(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.timeout(any(Duration.class))).thenReturn(mockBuilder);
        when(mockBuilder.maxRetries(anyInt())).thenReturn(mockBuilder);
        when(mockBuilder.temperature(anyDouble())).thenReturn(mockBuilder);
        // build() returns OpenAiChatModel; use thenAnswer to return a ChatModel mock
        when(mockBuilder.build()).thenAnswer(inv -> chatModel);

        try (MockedStatic<OpenAiChatModel> staticMock = mockStatic(OpenAiChatModel.class)) {
            staticMock.when(OpenAiChatModel::builder).thenReturn(mockBuilder);
            return new FallbackModelClient("openai-compatible", "gpt-4o",
                "http://localhost:8080", "test-key", 30, 2, 0.5);
        }
    }

    @Test
    @DisplayName("Should return text response for plain text AI message")
    void shouldReturnTextResponseForPlainText() {
        FallbackModelClient client = createClientWithMockedModel();

        AiMessage aiMessage = AiMessage.from("Hello, world!");
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Hi"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        assertThat(result.content()).isEqualTo("Hello, world!");
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("Should return empty text when AI message text is null")
    void shouldReturnEmptyTextWhenAiMessageTextIsNull() {
        FallbackModelClient client = createClientWithMockedModel();

        // AiMessage with only tool execution requests has null text
        AiMessage aiMessage = AiMessage.from(List.<ToolExecutionRequest>of());
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Hi"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        // No tool execution requests → text path with null → empty string
        assertThat(result.content()).isEqualTo("");
        assertThat(result.hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("Should return tool calls when AI message has tool execution requests")
    void shouldReturnToolCallsWhenAiMessageHasToolRequests() {
        FallbackModelClient client = createClientWithMockedModel();

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
            .id("call-1").name("web_search").arguments("{\"query\":\"test\"}").build();
        AiMessage aiMessage = AiMessage.from(List.of(toolReq));
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Search for test"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-1");
        assertThat(result.toolCalls().get(0).name()).isEqualTo("web_search");
        assertThat(result.toolCalls().get(0).arguments()).isEqualTo("{\"query\":\"test\"}");
        // No text → empty content
        assertThat(result.content()).isEqualTo("");
    }

    @Test
    @DisplayName("Should return text and tool calls when AI message has both")
    void shouldReturnTextAndToolCallsWhenAiMessageHasBoth() {
        FallbackModelClient client = createClientWithMockedModel();

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
            .id("call-2").name("bash").arguments("{}").build();
        AiMessage aiMessage = AiMessage.from("Let me run a command.", List.of(toolReq));
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Run something"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        // Both text and tool calls should be present
        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.content()).isEqualTo("Let me run a command.");
        assertThat(result.hasContent()).isTrue();
    }

    @Test
    @DisplayName("Should return tool calls without text when text is blank")
    void shouldReturnToolCallsWithoutTextWhenTextIsBlank() {
        FallbackModelClient client = createClientWithMockedModel();

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
            .id("call-3").name("file_read").arguments("{}").build();
        // AiMessage.from with empty text and tool requests
        AiMessage aiMessage = AiMessage.from("", List.of(toolReq));
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Read a file"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        assertThat(result.hasToolCalls()).isTrue();
        // Blank text → should use ChatResponse.toolCalls() which sets content to ""
        assertThat(result.content()).isEqualTo("");
    }

    @Test
    @DisplayName("Should rethrow exception from chat model")
    void shouldRethrowExceptionFromChatModel() {
        FallbackModelClient client = createClientWithMockedModel();

        when(chatModel.chat(any(ChatRequest.class)))
            .thenThrow(new RuntimeException("Connection refused"));

        List<Message> messages = List.of(Message.user("Hi"));

        assertThatThrownBy(() -> client.complete(messages, List.of(), ModelRequestOptions.empty()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("Should return correct model name from getModelName()")
    void shouldReturnCorrectModelName() {
        FallbackModelClient client = createClientWithMockedModel();

        assertThat(client.getModelName()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("Should handle null tools list without error")
    void shouldHandleNullToolsList() {
        FallbackModelClient client = createClientWithMockedModel();

        AiMessage aiMessage = AiMessage.from("Response with no tools");
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Hi"));
        // Pass null tools — should be handled by the null check in complete()
        ChatResponse result = client.complete(messages, null, ModelRequestOptions.empty());

        assertThat(result.content()).isEqualTo("Response with no tools");
        assertThat(result.hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("Should handle multiple tool execution requests in one response")
    void shouldHandleMultipleToolExecutionRequests() {
        FallbackModelClient client = createClientWithMockedModel();

        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
            .id("call-a").name("web_search").arguments("{\"q\":\"a\"}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
            .id("call-b").name("bash").arguments("{\"cmd\":\"ls\"}").build();
        AiMessage aiMessage = AiMessage.from(List.of(req1, req2));
        dev.langchain4j.model.chat.response.ChatResponse lcResponse =
            new Builder().aiMessage(aiMessage).build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse);

        List<Message> messages = List.of(Message.user("Do two things"));
        ChatResponse result = client.complete(messages, List.of(), ModelRequestOptions.empty());

        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).id()).isEqualTo("call-a");
        assertThat(result.toolCalls().get(1).id()).isEqualTo("call-b");
    }

    @Test
    @DisplayName("Should create client from FallbackConfig via factory method")
    void shouldCreateClientFromFallbackConfig() {
        FallbackConfig config = new FallbackConfig();
        config.setProvider("openai-compatible");
        config.setModel("claude-3");
        config.setBaseUrl("http://fallback:8080");
        config.setApiKey("fallback-key");

        AgentProperties properties = new AgentProperties();
        properties.getModel().setTimeoutSeconds(60);
        properties.getModel().setMaxRetries(5);
        properties.getModel().setTemperature(0.3);

        OpenAiChatModel.OpenAiChatModelBuilder mockBuilder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
        when(mockBuilder.baseUrl(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.apiKey(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.modelName(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.timeout(any(Duration.class))).thenReturn(mockBuilder);
        when(mockBuilder.maxRetries(anyInt())).thenReturn(mockBuilder);
        when(mockBuilder.temperature(anyDouble())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenAnswer(inv -> chatModel);

        try (MockedStatic<OpenAiChatModel> staticMock = mockStatic(OpenAiChatModel.class)) {
            staticMock.when(OpenAiChatModel::builder).thenReturn(mockBuilder);

            FallbackModelClient client = FallbackModelClient.from(config, properties);

            assertThat(client.getModelName()).isEqualTo("claude-3");
        }
    }
}