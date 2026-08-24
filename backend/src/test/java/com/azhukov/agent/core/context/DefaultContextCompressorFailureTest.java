package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * h61/h62: Tests for compression failure feedback and context quota exhaustion.
 */
class DefaultContextCompressorFailureTest {

    @Test
    void compress_fallsBackToTruncationWhenModelFails() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        Mockito.when(model.complete(Mockito.any(), Mockito.any())).thenThrow(new RuntimeException("boom"));
        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // head(1) + summary(1) + tail(3, Hermes floor) = 5
        assertThat(result).hasSize(5);
        assertThat(result.get(1).role()).isEqualTo(com.azhukov.agent.core.model.Role.SYSTEM);
        // h61: The summary should contain some content (not empty/silent)
        assertThat(result.get(1).content()).isNotEmpty();
    }

    @Test
    void compress_retriesOnQuotaExhaustion() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        // First call throws quota error, second succeeds
        Mockito.when(model.complete(Mockito.any(), Mockito.any()))
            .thenThrow(new RuntimeException("Rate limit exceeded: 429 Too Many Requests"))
            .thenReturn(ChatResponse.text("Summary after retry."));

        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // head(1) + summary(1) + tail(3, Hermes floor) = 5
        assertThat(result).hasSize(5);
        assertThat(result.get(1).content()).contains("Summary after retry.");
    }

    @Test
    void compress_preservesMessagesOnQuotaExhaustionAfterAllRetries() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        // Always throw quota error
        Mockito.when(model.complete(Mockito.any(), Mockito.any()))
            .thenThrow(new RuntimeException("429 Too Many Requests - quota exceeded"));

        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // h62: Should fall back to truncation (preserving content) rather than dropping messages
        assertThat(result).hasSize(5);
        assertThat(result.get(4).content()).isEqualTo("current");
        // The summary system message should have content (from fallback truncation)
        assertThat(result.get(1).content()).isNotEmpty();
    }

    @Test
    void compress_nonQuotaErrorStillFallsBackGracefully() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        Mockito.when(model.complete(Mockito.any(), Mockito.any()))
            .thenThrow(new RuntimeException("Model unavailable"));

        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // h61: Should fall back to truncation with a clear log message
        assertThat(result).hasSize(5);
        assertThat(result.get(1).content()).isNotEmpty();
    }

    @Test
    void compress_quotaErrorWithInsufficientQuotaMessage() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        Mockito.when(model.complete(Mockito.any(), Mockito.any()))
            .thenThrow(new RuntimeException("insufficient_quota: You exceeded your current quota"));

        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        // Should fall back to truncation preserving content
        assertThat(result).hasSize(5);
        assertThat(result.get(4).content()).isEqualTo("current");
    }

    @Test
    void compress_resourceExhaustedError() {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        Mockito.when(model.complete(Mockito.any(), Mockito.any()))
            .thenThrow(new RuntimeException("resource_exhausted: quota exceeded"));

        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var messages = List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        );
        var result = compressor.compress(messages, 100);
        assertThat(result).hasSize(5);
        assertThat(result.get(1).content()).isNotEmpty();
    }
}