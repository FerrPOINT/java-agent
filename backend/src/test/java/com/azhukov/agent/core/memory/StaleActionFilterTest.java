package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StaleActionFilterTest {

    @Test
    void collectPriorToolResults_emptyMessages_returnsEmpty() {
        StaleActionFilter.PriorToolResults results =
            StaleActionFilter.collectPriorToolResults(List.of());
        assertThat(results.toolCallIds()).isEmpty();
        assertThat(results.toolContents()).isEmpty();
    }

    @Test
    void collectPriorToolResults_nullMessages_returnsEmpty() {
        StaleActionFilter.PriorToolResults results =
            StaleActionFilter.collectPriorToolResults(null);
        assertThat(results.toolCallIds()).isEmpty();
        assertThat(results.toolContents()).isEmpty();
    }

    @Test
    void collectPriorToolResults_extractsToolCallIds() {
        List<Message> messages = List.of(
            Message.user("test"),
            Message.toolResult("call_1", "result content 1", 0),
            Message.toolResult("call_2", "result content 2", 0)
        );
        StaleActionFilter.PriorToolResults results =
            StaleActionFilter.collectPriorToolResults(messages);
        assertThat(results.toolCallIds()).contains("call_1", "call_2");
    }

    @Test
    void collectPriorToolResults_extractsContentsWithoutCallId() {
        List<Message> messages = List.of(
            new Message(Role.TOOL, "content without id", null, null, null, 0)
        );
        StaleActionFilter.PriorToolResults results =
            StaleActionFilter.collectPriorToolResults(messages);
        assertThat(results.toolCallIds()).isEmpty();
        assertThat(results.toolContents()).contains("content without id");
    }

    @Test
    void isStale_matchingCallId_returnsTrue() {
        List<Message> prior = List.of(
            Message.toolResult("call_1", "old result", 0)
        );
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(prior);

        Message newMsg = Message.toolResult("call_1", "new result", 0);
        assertThat(StaleActionFilter.isStale(newMsg, priorResults)).isTrue();
    }

    @Test
    void isStale_differentCallId_returnsFalse() {
        List<Message> prior = List.of(
            Message.toolResult("call_1", "old result", 0)
        );
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(prior);

        Message newMsg = Message.toolResult("call_2", "new result", 0);
        assertThat(StaleActionFilter.isStale(newMsg, priorResults)).isFalse();
    }

    @Test
    void isStale_matchingContentNoCallId_returnsTrue() {
        List<Message> prior = List.of(
            new Message(Role.TOOL, "same content", null, null, null, 0)
        );
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(prior);

        Message newMsg = new Message(Role.TOOL, "same content", null, null, null, 0);
        assertThat(StaleActionFilter.isStale(newMsg, priorResults)).isTrue();
    }

    @Test
    void isStale_differentContentNoCallId_returnsFalse() {
        List<Message> prior = List.of(
            new Message(Role.TOOL, "old content", null, null, null, 0)
        );
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(prior);

        Message newMsg = new Message(Role.TOOL, "new content", null, null, null, 0);
        assertThat(StaleActionFilter.isStale(newMsg, priorResults)).isFalse();
    }

    @Test
    void isStale_nonToolMessage_returnsFalse() {
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(List.of());

        Message userMsg = Message.user("test");
        assertThat(StaleActionFilter.isStale(userMsg, priorResults)).isFalse();
    }

    @Test
    void isStale_nullMessage_returnsFalse() {
        StaleActionFilter.PriorToolResults priorResults =
            StaleActionFilter.collectPriorToolResults(List.of());
        assertThat(StaleActionFilter.isStale(null, priorResults)).isFalse();
    }

    @Test
    void collectPriorToolResults_skipsNonToolMessages() {
        List<Message> messages = List.of(
            Message.user("user input"),
            Message.assistant("assistant response", 0),
            Message.system("system prompt"),
            Message.toolResult("call_1", "tool result", 0)
        );
        StaleActionFilter.PriorToolResults results =
            StaleActionFilter.collectPriorToolResults(messages);
        assertThat(results.toolCallIds()).containsExactly("call_1");
    }
}