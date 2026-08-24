package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

class DefaultContextCompressorEdgeCasesTest {

    private static final String REPEATED_CHAR = "x";

    private DefaultContextCompressor compressorWithFailingModel() {
        ModelClient model = mock(ModelClient.class);
        Mockito.when(model.complete(any(), any())).thenThrow(new RuntimeException("model unavailable"));
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMaxTokens(200);
        // Use small protect values so tests with few messages still compress
        properties.getContext().setProtectFirstN(1);
        properties.getContext().setProtectLastN(1);
        return new DefaultContextCompressor(model, null, properties);
    }

    private static int totalLength(List<Message> messages) {
        return messages.stream()
            .mapToInt(m -> m.content() != null ? m.content().length() : 0)
            .sum();
    }

    @Test
    void compressShortContextReturnsUnchanged() {
        DefaultContextCompressor compressor = compressorWithFailingModel();
        List<Message> messages = List.of(
            Message.system("System prompt."),
            Message.user("Hello!"),
            Message.assistant("Hi there!", 1)
        );

        List<Message> result = compressor.compress(messages, 1000);

        assertThat(result).isSameAs(messages);
        assertThat(totalLength(result)).isEqualTo(totalLength(messages));
    }

    @Test
    void compressLongContextReducesTotalLengthBelowTarget() {
        DefaultContextCompressor compressor = compressorWithFailingModel();
        // Three messages: a short system message, one very long user message (middle),
        // and a short final user message (tail). The summarized middle must fit under target.
        List<Message> messages = List.of(
            Message.system("Important system instructions: be helpful and concise."),
            Message.user(REPEATED_CHAR.repeat(2000)),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 1),
            Message.user(REPEATED_CHAR.repeat(500)),
            Message.user("short final user question")
        );

        int originalLength = totalLength(messages);
        int targetChars = 1000;
        assertThat(originalLength).isGreaterThan(targetChars);

        List<Message> result = compressor.compress(messages, targetChars);
        int compressedLength = totalLength(result);

        assertThat(compressedLength).isLessThan(originalLength);
        assertThat(result).isNotSameAs(messages);
        // The tail (last message) is preserved
        assertThat(result.get(result.size() - 1).content()).isEqualTo("short final user question");
    }

    @Test
    void compressEmptyContextReturnsEmpty() {
        DefaultContextCompressor compressor = compressorWithFailingModel();
        List<Message> empty = Collections.emptyList();

        List<Message> result = compressor.compress(empty, 500);

        assertThat(result).isSameAs(empty);
        assertThat(result).isEmpty();
    }

    @Test
    void compressKeepsFirstSystemMessage() {
        DefaultContextCompressor compressor = compressorWithFailingModel();
        List<Message> messages = List.of(
            Message.system("First system instruction."),
            Message.user("a".repeat(500)),
            Message.assistant("b".repeat(500), 1),
            Message.user("filler q ".repeat(100)),
            Message.assistant("filler a ".repeat(100), 2),
            Message.user("current user message")
        );

        List<Message> result = compressor.compress(messages, 100);

        // With protectFirstN=1: head = [system msg], protectLastN=1: tail = ["current user message"]
        // Original system message is preserved as first message (protected head)
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).isEqualTo("First system instruction.");
        // Summary system message is second
        assertThat(result.get(1).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(1).content()).contains("Earlier conversation (summarized):");
    }

    @Test
    void compressRespectsTargetCharsParameter() {
        DefaultContextCompressor compressor = compressorWithFailingModel();
        // Two messages whose total length is between 50 and 500 characters.
        // With protectFirstN=1, protectLastN=1: 2 messages <= 1+1=2 → skip compression
        List<Message> messages = List.of(
            Message.user("a".repeat(200)),
            Message.assistant("b".repeat(200), 1)
        );
        int originalLength = totalLength(messages);
        assertThat(originalLength).isBetween(51, 499);

        List<Message> resultAt500 = compressor.compress(messages, 500);
        List<Message> resultAt50 = compressor.compress(messages, 50);

        // 2 messages <= 2 (protectFirst+protectLast) → skip compression in both cases
        assertThat(resultAt500).isSameAs(messages);
        assertThat(totalLength(resultAt500)).isEqualTo(originalLength);
        // Also same since compression is skipped
        assertThat(resultAt50).isSameAs(messages);
    }
}