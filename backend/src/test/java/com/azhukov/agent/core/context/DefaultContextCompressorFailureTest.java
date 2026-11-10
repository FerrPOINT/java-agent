package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
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
    @Tag("slow")
    void smallerCeilingInterruptsTheActiveModelCall() throws Exception {
        var model = mock(com.azhukov.agent.core.client.ModelClient.class);
        var interrupted = new java.util.concurrent.CountDownLatch(1);
        when(model.complete(any(), any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(4_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return ChatResponse.text("late model summary");
        });
        var props = new AgentProperties();
        props.getContext().setProtectFirstN(1);
        props.getContext().setProtectLastN(1);
        props.getCompression().setSummaryTimeoutSeconds(10);
        props.getCompression().setTotalCeilingSeconds(1);
        var compressor = new DefaultContextCompressor(model, null, props);
        var result = compressor.compress(List.of(
            Message.user("a".repeat(2000)),
            Message.assistant("b".repeat(2000), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current")
        ), 100);

        assertThat(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(result.getLast().content()).isEqualTo("current");
        assertThat(result).noneMatch(message -> "late model summary".equals(message.content()));
    }

    @Test
    @Tag("slow")
    void timeoutCancelsTheProviderHttpRequest() throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(4_000);
            var disconnected = executor.submit(() -> {
                try (var connection = server.accept()) {
                    connection.setSoTimeout(4_000);
                    var request = new java.io.BufferedInputStream(connection.getInputStream());
                    var line = new StringBuilder();
                    int contentLength = 0;
                    for (int c; (c = request.read()) >= 0;) {
                        if (c != '\n') {
                            if (c != '\r') line.append((char) c);
                            continue;
                        }
                        if (line.isEmpty()) break;
                        String header = line.toString().toLowerCase(java.util.Locale.ROOT);
                        if (header.startsWith("content-length:")) {
                            contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                        }
                        line.setLength(0);
                    }
                    assertThat(contentLength).isPositive();
                    assertThat(request.readNBytes(contentLength)).hasSize(contentLength);
                    return request.read() == -1;
                }
            });
            var props = new AgentProperties();
            props.getModel().setApiKey("fixture-key");
            props.getModel().setBaseUrl("http://localhost:" + server.getLocalPort() + "/v1");
            props.getModel().setModelName("fixture-model");
            props.getContext().setProtectFirstN(1);
            props.getContext().setProtectLastN(1);
            props.getCompression().setSummaryTimeoutSeconds(10);
            props.getCompression().setTotalCeilingSeconds(2);
            var model = new com.azhukov.agent.client.langchain4j.LangChain4jModelClient(
                props, null, null, null);
            var compressor = new DefaultContextCompressor(model, null, props);

            var result = compressor.compress(List.of(
                Message.user("a".repeat(2000)), Message.assistant("b".repeat(2000), 1),
                Message.user("filler q ".repeat(100)), Message.assistant("filler a ".repeat(100), 2),
                Message.user("current")), 100);

            assertThat(disconnected.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result.getLast().content()).isEqualTo("current");
        }
    }

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
    @Tag("slow") // Exercises real retry/backoff or timeout waits; run manually.
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
    @Tag("slow") // Exercises real retry/backoff or timeout waits; run manually.
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
    @Tag("slow") // Exercises real retry/backoff or timeout waits; run manually.
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