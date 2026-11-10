package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.ContextEngine;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FallbackModelCallerTest {

    @Test
    void callSanitizesRetryContextImmediatelyBeforeProviderRequest() {
        AgentProperties properties = new AgentProperties();
        properties.getError().setRetryAttempts(0);
        ModelClient client = mock(ModelClient.class);
        when(client.complete(any(), any(), any())).thenReturn(new ChatResponse("ok", List.of(), "stop"));

        FallbackModelCaller caller = new FallbackModelCaller(
            mock(ErrorClassifier.class), properties, mock(ContextCompressor.class), mock(ContextEngine.class));
        List<Message> malformed = List.of(
            Message.user("first"),
            Message.user("second"),
            Message.assistantToolCalls(List.of(new ToolCall("call-1", "test", "{}")), 1),
            Message.toolResult("orphan", "must not reach provider", 1));

        caller.call(new FallbackModelCaller.ModelCallContext(client, new FallbackManager(List.of(), "p", "m", "", "")),
            malformed, List.<ToolDefinition>of(), ModelRequestOptions.empty(), () -> true, ModelClient::complete);

        ArgumentCaptor<List<Message>> context = ArgumentCaptor.forClass(List.class);
        verify(client).complete(context.capture(), any(), any());
        assertThat(context.getValue()).hasSize(2);
        assertThat(context.getValue().getFirst().content()).isEqualTo("first\n\nsecond");
        assertThat(context.getValue()).noneMatch(message -> message.role().name().equals("TOOL"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void triesTheOnlyFallbackOnceAfterThePrimaryRejectsTheModel(int ordinaryRetries) {
        var properties = new AgentProperties();
        properties.getError().setRetryAttempts(ordinaryRetries);
        var reserve = new com.azhukov.agent.config.FallbackConfig();
        reserve.setModel("reserve");
        reserve.setBaseUrl("http://127.0.0.1:1/v1");
        reserve.setApiKey("fixture");
        var primary = mock(ModelClient.class);
        var context = new FallbackModelCaller.ModelCallContext(primary,
            new FallbackManager(List.of(reserve), "openai-compatible", "primary", "", ""));
        var caller = new FallbackModelCaller(new ErrorClassifier(), properties, null, null);
        var attempts = new java.util.ArrayList<ModelClient>();

        var response = caller.call(context, List.of(Message.user("fixture")), List.of(),
            ModelRequestOptions.empty(), () -> true, (client, messages, tools, options) -> {
                attempts.add(client);
                if (client == primary) {
                    throw new IllegalStateException("404 model not found");
                }
                return ChatResponse.text("reserve response");
            });

        assertThat(response.content()).isEqualTo("reserve response");
        assertThat(attempts).containsExactly(primary, context.client());
        assertThat(context.client().getModelName()).isEqualTo("reserve");
    }

    @Test
    void overloadUsesTheAvailabilityBudgetEvenWhenNormalRetriesAreOff() {
        AgentProperties properties = new AgentProperties();
        properties.getError().setRetryAttempts(0);
        properties.getError().setAvailabilityRetryAttempts(2);
        properties.getError().setRetryDelayMs(1);
        properties.getError().setRetryCapMs(1);
        ModelClient client = mock(ModelClient.class);
        when(client.complete(any(), any(), any()))
            .thenThrow(new RuntimeException("529 overloaded"))
            .thenReturn(ChatResponse.text("available"));
        var caller = new FallbackModelCaller(
            new ErrorClassifier(), properties, mock(ContextCompressor.class), mock(ContextEngine.class));
        var context = new FallbackModelCaller.ModelCallContext(client,
            new FallbackManager(List.of(), "p", "m", "", ""));

        ChatResponse response = caller.call(context, List.of(Message.user("request")), List.of(),
            ModelRequestOptions.empty(), () -> true, ModelClient::complete);

        assertThat(response.content()).isEqualTo("available");
        verify(client, org.mockito.Mockito.times(2)).complete(any(), any(), any());
    }

    @Test
    void retryReceivesSanitizedContextAfterOverflowRecovery() {
        AgentProperties properties = new AgentProperties();
        properties.getError().setRetryAttempts(1);
        ModelClient client = mock(ModelClient.class);
        AtomicReference<List<Message>> firstAttempt = new AtomicReference<>();
        when(client.complete(any(), any(), any())).thenAnswer(invocation -> {
            if (firstAttempt.compareAndSet(null, invocation.getArgument(0))) {
                throw new RuntimeException("connection reset");
            }
            return ChatResponse.text("ok");
        });

        FallbackModelCaller caller = new FallbackModelCaller(
            new ErrorClassifier(), properties, mock(ContextCompressor.class), mock(ContextEngine.class));
        List<Message> malformed = List.of(Message.user("first"), Message.user("second"));

        caller.call(new FallbackModelCaller.ModelCallContext(client, new FallbackManager(List.of(), "p", "m", "", "")),
            malformed, List.<ToolDefinition>of(), ModelRequestOptions.empty(), () -> true, ModelClient::complete);

        ArgumentCaptor<List<Message>> calls = ArgumentCaptor.forClass(List.class);
        verify(client, org.mockito.Mockito.times(2)).complete(calls.capture(), any(), any());
        assertThat(calls.getAllValues()).allSatisfy(context ->
            assertThat(context).singleElement().extracting(Message::content).isEqualTo("first\n\nsecond"));
    }
}
