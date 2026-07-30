package com.azhukov.agent.service;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationCompressorTest {

    @Mock private ModelClient modelClient;
    private ConversationCompressor compressor;

    @BeforeEach
    void setUp() {
        compressor = new ConversationCompressor(modelClient);
    }

    @Test
    void compressShortHistoryReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello")
        );
        List<Message> result = compressor.compress(messages, null);
        assertThat(result).hasSize(2);
    }

    @Test
    void compressFullWithSummary() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Summary of conversation", null));
        List<Message> messages = List.of(
            Message.system("System prompt"),
            Message.user("Tell me about X"),
            Message.assistant("X is...", 1),
            Message.user("More about Y"),
            Message.assistant("Y is...", 2),
            Message.user("Final question")
        );
        List<Message> result = compressor.compress(messages, null);
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThan(messages.size());

        // The summary should be injected into the system message
        Message systemMsg = result.get(0);
        assertThat(systemMsg.role()).isEqualTo(Role.SYSTEM);
        assertThat(systemMsg.content()).contains("Summary of conversation");
        // The original system prompt should be preserved
        assertThat(systemMsg.content()).contains("System prompt");
        // The [Conversation Summary] marker should be present
        assertThat(systemMsg.content()).contains("[Conversation Summary]");

        // The last user message should be kept intact
        Message lastMsg = result.get(result.size() - 1);
        assertThat(lastMsg.role()).isEqualTo(Role.USER);
        assertThat(lastMsg.content()).isEqualTo("Final question");
    }

    @Test
    void compressWithFocusTopic() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Focused summary", null));
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Q1"),
            Message.assistant("A1", 1),
            Message.user("Q2"),
            Message.assistant("A2", 2),
            Message.user("Q3")
        );
        List<Message> result = compressor.compress(messages, "topic-X");
        assertThat(result).isNotEmpty();
        // Summary should appear in system message
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).contains("Focused summary");
        // Last user message preserved
        assertThat(result.get(result.size() - 1).role()).isEqualTo(Role.USER);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("Q3");
    }

    @Test
    void compressPartialKeepsLastN() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Summary of old messages", null));
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Q1"),
            Message.assistant("A1", 1),
            Message.user("Q2"),
            Message.assistant("A2", 2),
            Message.user("Q3"),
            Message.assistant("A3", 3)
        );
        List<Message> result = compressor.compressPartial(messages, 2);
        assertThat(result).isNotEmpty();
        assertThat(result.size()).isLessThan(messages.size());

        // System message should contain summary
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).contains("Summary of old messages");
        assertThat(result.get(0).content()).contains("[Earlier Conversation Summary]");

        // The last 2 messages should be kept verbatim
        assertThat(result.get(result.size() - 2).content()).isEqualTo("Q3");
        assertThat(result.get(result.size() - 1).content()).isEqualTo("A3");
    }

    @Test
    void compressPartialWithFewMessagesReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Q1")
        );
        List<Message> result = compressor.compressPartial(messages, 5);
        assertThat(result).hasSize(2);
    }

    @Test
    void compressWithoutSystemMessageCreatesSummaryAsSystem() {
        when(modelClient.complete(any(), any())).thenReturn(new ChatResponse("Standalone summary", null));
        List<Message> messages = List.of(
            Message.user("Q1"),
            Message.assistant("A1", 1),
            Message.user("Q2"),
            Message.assistant("A2", 2),
            Message.user("Q3")
        );
        List<Message> result = compressor.compress(messages, null);
        // First message should be a system message containing the summary
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).contains("Standalone summary");
        assertThat(result.get(0).content()).contains("[Conversation Summary]");
    }
}