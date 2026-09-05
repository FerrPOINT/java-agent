package com.azhukov.agent.client.langchain4j;

import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEBT-2 (M32): fallback-model token usage must reach the turn usage collector.
 * Before the fix, FallbackModelClient.complete() dropped tokenUsage entirely —
 * every fallback completion was invisible to /usage, /credits and usage_log.
 */
class FallbackModelClientUsageTest {

    @Test
    void fallbackCompletionReportsUsage() {
        ChatModel chatModel = mock(ChatModel.class);
        dev.langchain4j.model.chat.response.ChatResponse lc =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("fallback answer"))
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(100, 50))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lc);

        List<LangChain4jModelClient.Usage> recorded = new CopyOnWriteArrayList<>();
        FallbackModelClient client = new FallbackModelClient(
            "openai-compatible", "fallback-model", chatModel, recorded::add);

        ChatResponse response = client.complete(List.of(Message.user("hi")), null, null);

        assertThat(response.content()).isEqualTo("fallback answer");
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).provider()).isEqualTo("openai-compatible");
        assertThat(recorded.get(0).model()).isEqualTo("fallback-model");
        assertThat(recorded.get(0).promptTokens()).isEqualTo(100);
        assertThat(recorded.get(0).completionTokens()).isEqualTo(50);
    }

    @Test
    void nullUsageInResponseIsNotReported() {
        ChatModel chatModel = mock(ChatModel.class);
        dev.langchain4j.model.chat.response.ChatResponse lc =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("no usage here"))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lc);

        List<LangChain4jModelClient.Usage> recorded = new CopyOnWriteArrayList<>();
        FallbackModelClient client = new FallbackModelClient(
            "p", "m", chatModel, recorded::add);

        ChatResponse response = client.complete(List.of(Message.user("hi")), null, null);
        assertThat(response.content()).isEqualTo("no usage here");
        assertThat(recorded).isEmpty();
    }

    @Test
    void nullConsumerStillCompletes() {
        ChatModel chatModel = mock(ChatModel.class);
        dev.langchain4j.model.chat.response.ChatResponse lc =
            dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .tokenUsage(new dev.langchain4j.model.output.TokenUsage(1, 1))
                .build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(lc);

        FallbackModelClient client = new FallbackModelClient("p", "m", chatModel, null);
        ChatResponse response = client.complete(List.of(Message.user("hi")), null, null);
        assertThat(response.content()).isEqualTo("ok");
    }
}
