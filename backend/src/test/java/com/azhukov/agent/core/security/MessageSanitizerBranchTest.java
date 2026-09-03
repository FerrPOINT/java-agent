package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch coverage tests for {@link MessageSanitizer}.
 * Covers thinking-only messages, role alternation, tool call repair, and null handling.
 */
class MessageSanitizerBranchTest {

    private MessageSanitizer sanitizer() {
        return new MessageSanitizer(new SecretRedactor(new AgentProperties()));
    }

    @Test
    void sanitizeNullList_returnsNull() {
        MessageSanitizer s = sanitizer();
        assertThat(s.sanitize((List<Message>) null)).isNull();
    }

    @Test
    void sanitizeEmptyList_returnsEmptyList() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(new ArrayList<>());
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeListWithNullMessage_throwsNPEInRoleAlternation() {
        MessageSanitizer s = sanitizer();
        // Null message in list causes NPE in repairRoleAlternation when accessing previous.role()
        // This is an existing edge case — the sanitizer doesn't guard against null in the list
        assertThatThrownBy(() -> s.sanitize(List.of(Message.user("hello"), null)))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void sanitizeThinkingOnlyAssistantMessage_dropsIt() {
        MessageSanitizer s = sanitizer();
        Message thinking = Message.assistant("<think>some reasoning</think>", 0);
        List<Message> result = s.sanitize(List.of(thinking));
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeThinkingOnlyWithEmptyContent_dropsIt() {
        MessageSanitizer s = sanitizer();
        Message thinking = Message.assistant("", 0);
        List<Message> result = s.sanitize(List.of(thinking));
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeThinkingOnlyWithBlankContent_dropsIt() {
        MessageSanitizer s = sanitizer();
        Message thinking = Message.assistant("   ", 0);
        List<Message> result = s.sanitize(List.of(thinking));
        assertThat(result).isEmpty();
    }

    @Test
    void sanitizeAssistantWithToolCalls_notDropped() {
        MessageSanitizer s = sanitizer();
        Message withTools = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "search", "{}")), 0);
        List<Message> result = s.sanitize(List.of(withTools));
        assertThat(result).hasSize(1);
    }

    @Test
    void sanitizeAssistantWithContentAndThinking_notDropped() {
        MessageSanitizer s = sanitizer();
        Message msg = Message.assistant("<think>reasoning</think>actual content", 0);
        List<Message> result = s.sanitize(List.of(msg));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).contains("actual content");
    }

    @Test
    void sanitizeRepairsRoleAlternation_consecutiveUserMessages() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(List.of(
            Message.user("msg1"),
            Message.user("msg2"),
            Message.user("msg3")
        ));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo("msg1\n\nmsg2\n\nmsg3");
    }

    @Test
    void sanitizeRepairsRoleAlternation_consecutiveAssistantMessages() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(List.of(
            Message.assistant("msg1", 0),
            Message.assistant("msg2", 0)
        ));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.get(0).content()).isEqualTo("msg1\nmsg2");
    }

    @Test
    void sanitizePreservesConsecutiveToolResultsForOneAssistantBatch() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(List.of(
            Message.assistantToolCalls(List.of(
                new ToolCall("call-1", "read_file", "{}"),
                new ToolCall("call-2", "search_files", "{}")), 1),
            Message.toolResult("call-1", "first", 1),
            Message.toolResult("call-2", "second", 1)
        ));

        assertThat(result).hasSize(3);
        assertThat(result.get(1).role()).isEqualTo(Role.TOOL);
        assertThat(result.get(2).role()).isEqualTo(Role.TOOL);
        assertThat(result.get(1).toolCallId()).isEqualTo("call-1");
        assertThat(result.get(2).toolCallId()).isEqualTo("call-2");
    }

    @Test
    void sanitizeSingleMessage_noAlternationRepair() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(List.of(Message.user("hello")));
        assertThat(result).hasSize(1);
    }

    @Test
    void sanitizeMessageWithNullContent_returnsEmptyContent() {
        MessageSanitizer s = sanitizer();
        Message msg = new Message(Role.USER, null, null, null, null, 0);
        Message result = s.sanitize(msg);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void sanitizeMessageWithToolCalls_repairsUnbalancedBraces() {
        MessageSanitizer s = sanitizer();
        // Create a message with unbalanced JSON braces in tool call arguments
        String unbalanced = "{\"path\":\"/tmp\",\"content\":\"hello\"";
        Message msg = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "write_file", unbalanced)), 0);
        Message result = s.sanitize(msg);
        assertThat(result.toolCalls()).isNotNull();
        assertThat(result.toolCalls()).hasSize(1);
        // The repaired arguments should have balanced braces
        assertThat(result.toolCalls().get(0).arguments()).contains("}");
    }

    @Test
    void sanitizeMessageWithBalancedToolCalls_notModified() {
        MessageSanitizer s = sanitizer();
        String balanced = "{\"path\":\"/tmp\",\"content\":\"hello\"}";
        Message msg = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "write_file", balanced)), 0);
        Message result = s.sanitize(msg);
        assertThat(result.toolCalls().get(0).arguments()).isEqualTo(balanced);
    }

    @Test
    void sanitizeMessageWithNullToolCallArguments_keepsArgumentsAsIs() {
        MessageSanitizer s = sanitizer();
        // ToolCall requires non-null arguments, so we pass empty string
        Message msg = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "search", "")), 0);
        Message result = s.sanitize(msg);
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).arguments()).isEqualTo("");
    }

    @Test
    void sanitizeMessageWithToolCallsAndEscapedQuotes_repairsCorrectly() {
        MessageSanitizer s = sanitizer();
        String unbalanced = "{\"content\":\"hello \\\"world\"";
        Message msg = Message.assistantWithToolCalls("",
            List.of(new ToolCall("call-1", "write_file", unbalanced)), 0);
        Message result = s.sanitize(msg);
        assertThat(result.toolCalls().get(0).arguments()).contains("}");
    }
}
