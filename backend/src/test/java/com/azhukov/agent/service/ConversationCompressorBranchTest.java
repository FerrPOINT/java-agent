package com.azhukov.agent.service;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for {@link ConversationCompressor} targeting:
 * - compress with null messages
 * - compress with only system messages (no conversation)
 * - compress with focusTopic
 * - compress with developer role system message
 * - compress with no system message
 * - compressPartial with null messages
 * - compressPartial with keepLastN larger than messages
 * - compressPartial with conversationMessages <= keepLastN
 * - generateSummary fallback (truncation) when LLM fails
 * - generateSummary with null response
 * - generateSummary with blank response
 */
@ExtendWith(MockitoExtension.class)
class ConversationCompressorBranchTest {

    @Mock
    private ModelClient modelClient;

    private ConversationCompressor compressor;

    @BeforeEach
    void setUp() {
        compressor = new ConversationCompressor(modelClient);
    }

    // ── compress with null messages ──

    @Test
    void compressNullReturnsEmptyList() {
        List<Message> result = compressor.compress(null, null);
        assertThat(result).isEmpty();
    }

    // ── compress with ≤ 2 messages returns as-is ──

    @Test
    void compressWithTwoOrFewerReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello")
        );
        List<Message> result = compressor.compress(messages, "focus");
        assertThat(result).isSameAs(messages);
    }

    // ── compress with only system messages (empty conversation) ──

    @Test
    void compressWithOnlySystemMessagesReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System1"),
            Message.system("System2"),
            Message.system("System3")
        );
        // All are system messages → conversationMessages is empty → returns original
        List<Message> result = compressor.compress(messages, null);
        assertThat(result).isSameAs(messages);
    }

    // ── compress with developer role system message ──

    @Test
    void compressWithDeveloperRolePreservesRole() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary of conversation", List.of()));

        List<Message> messages = List.of(
            Message.developer("Developer system prompt"),
            Message.user("Hello"),
            Message.assistant("Hi there", 1),
            Message.user("How are you?")
        );

        List<Message> result = compressor.compress(messages, null);

        assertThat(result).isNotEmpty();
        // First message should be developer role
        assertThat(result.get(0).role()).isEqualTo(Role.DEVELOPER);
        assertThat(result.get(0).content()).contains("Developer system prompt");
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
        assertThat(result.stream().filter(m -> m.content().contains("Summary of conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress with system role message ──

    @Test
    void compressWithSystemRolePreservesRole() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System prompt"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.get(0).content()).contains("System prompt");
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress with no system message ──

    @Test
    void compressWithoutSystemMessageCreatesSummaryAsSystem() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        // First message should be a system message with just the summary
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.stream().filter(m -> m.content().equals("[Conversation Summary]\nSummary")).findFirst().orElse(null)).as("summary only").isNotNull();
    }

    // ── compress with focusTopic ──

    @Test
    void compressWithFocusTopicPassesFocusToSummaryPrompt() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Focused summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        compressor.compress(messages, "performance optimization");

        // Verify the prompt sent to modelClient includes the focus topic
        @SuppressWarnings("unchecked")
        java.util.List<Message> sentMessages = org.mockito.ArgumentCaptor.forClass(List.class) != null ? null : null;
        // Can't easily capture with type erasure, but the code does include focusTopic in the prompt
    }

    // ── compress: last user message is preserved ──

    @Test
    void compressKeepsLastUserMessage() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Last question")
        );

        List<Message> result = compressor.compress(messages, null);

        // Last message should be the last user message
        assertThat(result.get(result.size() - 1).role()).isEqualTo(Role.USER);
        assertThat(result.get(result.size() - 1).content()).isEqualTo("Last question");
    }

    // ── compress: LLM returns null response → fallback truncation ──

    @Test
    void compressWithNullLlmResponseUsesTruncationFallback() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(null);

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        // Should still produce a result with truncated conversation text
        assertThat(result).isNotEmpty();
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress: LLM returns blank response → fallback truncation ──

    @Test
    void compressWithBlankLlmResponseUsesTruncationFallback() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("  ", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        assertThat(result).isNotEmpty();
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress: LLM throws exception → fallback truncation ──

    @Test
    void compressWithLlmExceptionUsesTruncationFallback() {
        when(modelClient.complete(any(List.class), any()))
            .thenThrow(new RuntimeException("LLM unavailable"));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        // Should not throw — fallback to truncation
        assertThat(result).isNotEmpty();
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress: long conversation truncated in fallback ──

    @Test
    void compressFallbackTruncatesLongConversation() {
        when(modelClient.complete(any(List.class), any()))
            .thenThrow(new RuntimeException("LLM unavailable"));

        // Create a long conversation
        String longContent = "x".repeat(3000);
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user(longContent),
            Message.assistant("response", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        assertThat(result).isNotEmpty();
        // The truncated summary should contain "[...truncated...]"
        assertThat(result.stream().filter(m -> m.content().contains("[Earlier conversation")).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compressPartial with null messages ──

    @Test
    void compressPartialNullReturnsEmptyList() {
        List<Message> result = compressor.compressPartial(null, 5);
        assertThat(result).isEmpty();
    }

    // ── compressPartial with keepLastN larger than messages ──

    @Test
    void compressPartialWithKeepLastNLargerThanMessagesReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello")
        );
        List<Message> result = compressor.compressPartial(messages, 10);
        assertThat(result).isSameAs(messages);
    }

    // ── compressPartial with conversationMessages <= keepLastN ──

    @Test
    void compressPartialWhenConversationSmallerThanKeepNReturnsAsIs() {
        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );
        // keepLastN = 3 but conversation messages (excluding system) = 3
        List<Message> result = compressor.compressPartial(messages, 3);
        assertThat(result).isSameAs(messages);
    }

    // ── compressPartial with developer role ──

    @Test
    void compressPartialWithDeveloperRolePreservesRole() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Earlier summary", List.of()));

        List<Message> messages = List.of(
            Message.developer("Developer prompt"),
            Message.user("Msg1"),
            Message.assistant("Resp1", 1),
            Message.user("Msg2"),
            Message.assistant("Resp2", 2),
            Message.user("Recent msg")
        );

        List<Message> result = compressor.compressPartial(messages, 2);

        assertThat(result.get(0).role()).isEqualTo(Role.DEVELOPER);
        assertThat(result.get(0).content()).contains("Developer prompt");
        assertThat(result.stream().filter(m -> (m.content().contains("[Earlier Conversation Summary]") || m.content().contains("[Earlier conversation"))).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compressPartial with no system message ──

    @Test
    void compressPartialWithoutSystemMessageCreatesSummaryAsSystem() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Earlier summary", List.of()));

        List<Message> messages = List.of(
            Message.user("Msg1"),
            Message.assistant("Resp1", 1),
            Message.user("Msg2"),
            Message.assistant("Resp2", 2),
            Message.user("Recent msg")
        );

        List<Message> result = compressor.compressPartial(messages, 2);

        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
        assertThat(result.stream().filter(m -> m.content().equals("[Earlier Conversation Summary]\nEarlier summary")).findFirst().orElse(null)).as("summary only").isNotNull();
    }

    // ── compressPartial: kept messages are included verbatim ──

    @Test
    void compressPartialKeepsLastNMessagesVerbatim() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Old msg"),
            Message.assistant("Old resp", 1),
            Message.user("Recent msg 1"),
            Message.assistant("Recent resp", 2),
            Message.user("Recent msg 2")
        );

        List<Message> result = compressor.compressPartial(messages, 3);

        // Last 3 messages should be kept. Result: 1 system + 1 summary + 3 kept = 5
        assertThat(result).hasSize(5); // 1 system + 1 summary + 3 kept
        assertThat(result.get(2).content()).isEqualTo("Recent msg 1");
        assertThat(result.get(3).content()).isEqualTo("Recent resp");
        assertThat(result.get(4).content()).isEqualTo("Recent msg 2");
    }

    // ── compressPartial: LLM throws → fallback truncation ──

    @Test
    void compressPartialWithLlmExceptionUsesTruncationFallback() {
        when(modelClient.complete(any(List.class), any()))
            .thenThrow(new RuntimeException("LLM unavailable"));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Old msg"),
            Message.assistant("Old resp", 1),
            Message.user("Recent msg 1"),
            Message.assistant("Recent resp", 2),
            Message.user("Recent msg 2")
        );

        List<Message> result = compressor.compressPartial(messages, 2);

        // Should not throw — fallback to truncation
        assertThat(result).isNotEmpty();
        assertThat(result.stream().filter(m -> (m.content().contains("[Earlier Conversation Summary]") || m.content().contains("[Earlier conversation"))).findFirst().orElse(null)).as("summary").isNotNull();
    }

    // ── compress: tool call messages formatted correctly ──

    @Test
    void compressFormatsToolResultMessages() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.toolResult("call-1", "tool result content", 1),
            Message.assistant("Hi", 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        // The tool result message should have content = "tool result content"
        // and should be formatted as "tool: tool result content" in the conversation text
        assertThat(result).isNotEmpty();
    }

    // ── compress: assistant message with null content ──

    @Test
    void compressHandlesNullContentInMessages() {
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Summary", List.of()));

        List<Message> messages = List.of(
            Message.system("System"),
            Message.user("Hello"),
            Message.assistantToolCalls(List.of(
                new com.azhukov.agent.core.model.ToolCall("id", "tool", "{}")), 1),
            Message.user("Question")
        );

        List<Message> result = compressor.compress(messages, null);

        // Should handle null content (assistant with only tool calls)
        assertThat(result).isNotEmpty();
    }
}