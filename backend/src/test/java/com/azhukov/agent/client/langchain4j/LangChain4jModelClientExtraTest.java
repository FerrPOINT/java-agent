package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Extra branch-coverage tests for LangChain4jModelClient.
 */
class LangChain4jModelClientExtraTest {

    private final AgentProperties properties = new AgentProperties();
    private final ChatModel chatModel = mock(ChatModel.class);
    private final StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
    private final AtomicReference<LangChain4jModelClient.Usage> usage = new AtomicReference<>();

    private LangChain4jModelClient client() {
        return new LangChain4jModelClient(chatModel, streamingChatModel, properties, usage::set, null, null);
    }

    private dev.langchain4j.model.chat.response.ChatResponse lcResponse(String text) {
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
            .aiMessage(AiMessage.from(text)).build();
    }

    @Test
    void completeWithNullToolsDoesNotThrow() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        var r = client.complete(List.of(Message.user("hi")), null);
        assertThat(r.content()).isEqualTo("ok");
    }

    @Test
    void completeWithSystemMessage() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("sys-ok"));

        var r = client.complete(
            List.of(Message.system("you are helpful"), Message.user("hi")),
            List.of());
        assertThat(r.content()).isEqualTo("sys-ok");
    }

    @Test
    void completeWithAssistantContentOnlyMessage() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("cont-ok"));

        var r = client.complete(
            List.of(Message.user("hi"), Message.assistant("hello back", 0)),
            List.of());
        assertThat(r.content()).isEqualTo("cont-ok");
    }

    @Test
    void completeWithAssistantToolCallsMessage() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("tc-ok"));

        Message assistantWithTools = Message.assistantWithToolCalls("calling tool",
            List.of(new ToolCall("1", "search", "{\"q\":\"x\"}")), 0);
        var r = client.complete(
            List.of(Message.user("hi"), assistantWithTools),
            List.of());
        assertThat(r.content()).isEqualTo("tc-ok");
    }

    @Test
    void completePreservesAssistantTextOnToolCallReplay() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("tc-ok"));

        Message assistantWithTools = Message.assistantWithToolCalls("calling tool",
            List.of(new ToolCall("1", "search", "{\"q\":\"x\"}")), 0);

        client.complete(
            List.of(Message.user("hi"), assistantWithTools, Message.toolResult("1", "result text", 0)),
            List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        AiMessage replayedAssistant = (AiMessage) request.getValue().messages().get(1);
        assertThat(replayedAssistant.text()).isEqualTo("calling tool");
        assertThat(replayedAssistant.toolExecutionRequests()).hasSize(1);
    }

    @Test
    void completeWithToolResultMessage() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("tr-ok"));

        Message toolResult = Message.toolResult("1", "result text", 0);
        var r = client.complete(
            List.of(Message.user("hi"), Message.assistant("calling", 0), toolResult),
            List.of());
        assertThat(r.content()).isEqualTo("tr-ok");
    }

    @Test
    void streamWithToolCallsOnComplete() {
        LangChain4jModelClient client = new LangChain4jModelClient(null, streamingChatModel, properties, usage::set, null, null);

        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> completes = new ArrayList<>();

        ToolExecutionRequest req = ToolExecutionRequest.builder().id("1").name("search").arguments("{}").build();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(req))).build());
            return null;
        }).when(streamingChatModel).doChat(any(ChatRequest.class), any());

        client.stream(List.of(Message.user("search")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { }
            @Override public void onToolCalls(List<ToolCall> calls) { toolCalls.addAll(calls); }
            @Override public void onComplete() { completes.add("done"); }
            @Override public void onError(Throwable error) { }
        });

        assertThat(toolCalls).hasSize(1);
        assertThat(completes).containsExactly("done");
    }

    @Test
    void streamWithEmptyContentAndTextOnComplete() {
        LangChain4jModelClient client = new LangChain4jModelClient(null, streamingChatModel, properties, usage::set, null, null);

        List<String> tokens = new ArrayList<>();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("late-text")).build());
            return null;
        }).when(streamingChatModel).doChat(any(ChatRequest.class), any());

        client.stream(List.of(Message.user("hi")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { tokens.add(token); }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { }
        });

        assertThat(tokens).contains("late-text");
    }

    @Test
    void streamOnErrorPropagates() {
        LangChain4jModelClient client = new LangChain4jModelClient(null, streamingChatModel, properties, usage::set, null, null);

        List<Throwable> errors = new ArrayList<>();

        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onError(new RuntimeException("stream broke"));
            return null;
        }).when(streamingChatModel).doChat(any(ChatRequest.class), any());

        assertThatThrownBy(() -> client.stream(List.of(Message.user("hi")), List.of(), new StreamingResponseHandler() {
            @Override public void onToken(String token) { }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { errors.add(error); }
        })).isInstanceOf(RuntimeException.class)
          .hasMessageContaining("stream broke");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getMessage()).isEqualTo("stream broke");
    }

    @Test
    void toolSpecificationWithEmptySchema() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        // Empty schema (no properties, no required) → toJsonSchema returns empty JsonObjectSchema
        ToolDefinition def = new ToolDefinition("t", "desc", java.util.Map.of());

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("go")), List.of(def));
        verify(chatModel).chat(any(ChatRequest.class));
    }

    @Test
    void toolSpecificationWithNullRequiredAndNonMapProperty() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        ToolDefinition def = new ToolDefinition("t", "desc", Map.of(
            "type", "object",
            "properties", Map.of("name", "just-a-string")
        ));

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("go")), List.of(def));
        verify(chatModel).chat(any(ChatRequest.class));
    }

    @Test
    void persistUsageWithTokenUsage() {
        properties.getModel().setModelName("gpt-4o");
        properties.getModel().setProvider("test-provider");
        LangChain4jModelClient client = client();

        dev.langchain4j.model.chat.response.ChatResponse response =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .tokenUsage(new TokenUsage(100, 50))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        client.complete(List.of(Message.user("hi")), List.of());

        assertThat(usage.get()).isNotNull();
        assertThat(usage.get().promptTokens()).isEqualTo(100);
        assertThat(usage.get().completionTokens()).isEqualTo(50);
        assertThat(usage.get().provider()).isEqualTo("test-provider");
    }

    @Test
    void completeUsesRouteTransportOverridesAndUsageIdentity() {
        properties.getModel().setModelName("global-model");
        properties.getModel().setProvider("global-provider");
        properties.getModel().setBaseUrl("https://global.example/v1");
        properties.getModel().setApiKey("sk-global");
        LangChain4jModelClient client = client();

        OpenAiChatModel routedModel = mock(OpenAiChatModel.class);
        OpenAiChatModel.OpenAiChatModelBuilder builder = mock(OpenAiChatModel.OpenAiChatModelBuilder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.apiKey(anyString())).thenReturn(builder);
        when(builder.modelName(anyString())).thenReturn(builder);
        when(builder.timeout(any(Duration.class))).thenReturn(builder);
        when(builder.maxRetries(anyInt())).thenReturn(builder);
        when(builder.temperature(anyDouble())).thenReturn(builder);
        when(builder.returnThinking(anyBoolean())).thenReturn(builder);
        when(builder.sendThinking(anyBoolean(), anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(routedModel);
        when(routedModel.chat(any(ChatRequest.class))).thenReturn(
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("route ok"))
                .tokenUsage(new TokenUsage(12, 7))
                .build());

        ModelRequestOptions options = new ModelRequestOptions(
            "openrouter/fast-model",
            null,
            null,
            null,
            null,
            null,
            null,
            "openrouter",
            "https://openrouter.example/v1",
            "sk-route-secret");

        try (MockedStatic<OpenAiChatModel> staticMock = mockStatic(OpenAiChatModel.class)) {
            staticMock.when(OpenAiChatModel::builder).thenReturn(builder);

            var result = client.complete(List.of(Message.user("hi")), List.of(), options);

            assertThat(result.content()).isEqualTo("route ok");
        }

        verify(builder).baseUrl("https://openrouter.example/v1");
        verify(builder).apiKey("sk-route-secret");
        verify(builder).modelName("openrouter/fast-model");
        verify(routedModel).chat(any(ChatRequest.class));
        verify(chatModel, never()).chat(any(ChatRequest.class));
        assertThat(usage.get()).isNotNull();
        assertThat(usage.get().provider()).isEqualTo("openrouter");
        assertThat(usage.get().model()).isEqualTo("openrouter/fast-model");
        assertThat(usage.get().promptTokens()).isEqualTo(12);
        assertThat(usage.get().completionTokens()).isEqualTo(7);
    }

    @Test
    void persistUsageSwallowsExceptions() {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = new LangChain4jModelClient(chatModel, streamingChatModel, properties,
            u -> { throw new RuntimeException("consumer broke"); }, null, null);

        dev.langchain4j.model.chat.response.ChatResponse response =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .tokenUsage(new TokenUsage(10, 5))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        var r = client.complete(List.of(Message.user("hi")), List.of());
        assertThat(r.content()).isEqualTo("ok");
    }

    @Test
    void legacyModelUsesMaxTokensWireFieldLikeHermes() {
        properties.getModel().setModelName("claude-3-opus");
        properties.getModel().setBaseUrl("https://openrouter.example/v1");
        properties.getModel().setMaxTokens(4096);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.maxOutputTokens()).isEqualTo(4096);
        assertThat(params.maxCompletionTokens()).isNull();
    }

    @Test
    void newerOpenAiFamilyUsesMaxCompletionTokensWireFieldLikeHermes() {
        properties.getModel().setModelName("openai/gpt-5.4");
        properties.getModel().setBaseUrl("https://openrouter.example/v1");
        properties.getModel().setMaxTokens(8192);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.maxOutputTokens()).isNull();
        assertThat(params.maxCompletionTokens()).isEqualTo(8192);
    }

    @Test
    void directOpenAiHostUsesMaxCompletionTokensEvenForOlderModelLikeHermes() {
        properties.getModel().setModelName("gpt-4");
        properties.getModel().setBaseUrl("https://api.openai.com/v1");
        properties.getModel().setMaxTokens(2048);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.maxOutputTokens()).isNull();
        assertThat(params.maxCompletionTokens()).isEqualTo(2048);
    }

    @Test
    void gpt4oOmitsTopLevelReasoningEffortLikeHermes() {
        properties.getModel().setModelName("gpt-4o");
        properties.getModel().setBaseUrl("https://api.openai.com/v1");
        properties.getModel().setReasoningEffort(70);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.reasoningEffort()).isNull();
    }

    @Test
    void directOpenAiReasoningModelSendsTopLevelReasoningEffortLikeHermes() {
        properties.getModel().setModelName("gpt-5.4");
        properties.getModel().setBaseUrl("https://api.openai.com/v1");
        properties.getModel().setReasoningEffort(70);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void openRouterModelOmitsTopLevelReasoningEffortLikeHermes() {
        properties.getModel().setModelName("openai/gpt-5.4");
        properties.getModel().setBaseUrl("https://openrouter.ai/api/v1");
        properties.getModel().setReasoningEffort(70);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.reasoningEffort()).isNull();
    }

    @Test
    void requestOptionsPassServiceTierSeparatelyFromFastMode() {
        properties.getModel().setModelName("claude-3-opus");
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));
        ModelRequestOptions options = new ModelRequestOptions(
            null, null, false, null, null, null, 3000,
            null, null, null, "priority");

        client.complete(List.of(Message.user("hi")), List.of(), options);

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.serviceTier()).isEqualTo("priority");
        assertThat(params.maxOutputTokens()).isEqualTo(3000);
    }

    @Test
    void regularModelSendsConfiguredTemperatureInRequestParameters() {
        properties.getModel().setModelName("gpt-4");
        properties.getModel().setBaseUrl("https://openrouter.example/v1");
        properties.getModel().setTemperature(0.25);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.temperature()).isEqualTo(0.25);
    }

    @Test
    void kimiModelOmitsTemperatureLikeHermes() {
        properties.getModel().setModelName("moonshot/kimi-k2.6");
        properties.getModel().setBaseUrl("https://api.moonshot.cn/v1");
        properties.getModel().setTemperature(0.7);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.temperature()).isNull();
        assertThat(params.reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void arceeTrinityThinkingUsesFixedTemperatureLikeHermes() {
        properties.getModel().setModelName("arcee/trinity-large-thinking");
        properties.getModel().setTemperature(0.9);
        LangChain4jModelClient client = client();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("ok"));

        client.complete(List.of(Message.user("hi")), List.of());

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(request.capture());
        OpenAiChatRequestParameters params = (OpenAiChatRequestParameters) request.getValue().parameters();
        assertThat(params.temperature()).isEqualTo(0.5);
    }

    @Test
    void analyzeImageAsyncReturnsText() throws Exception {
        properties.getModel().setModelName("gpt-4o");
        LangChain4jModelClient client = client();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lcResponse("image desc"));

        String result = client.analyzeImageAsync("data:image/png;base64,abc", "describe").get();
        assertThat(result).isEqualTo("image desc");
    }
}
