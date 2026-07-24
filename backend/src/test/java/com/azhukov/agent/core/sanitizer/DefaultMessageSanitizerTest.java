package com.azhukov.agent.core.sanitizer;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultMessageSanitizerTest {

    private final DefaultMessageSanitizer sanitizer = new DefaultMessageSanitizer();

    @Test
    void keepsValidSequence() {
        var messages = List.of(
            Message.system("sys"),
            Message.user("hi"),
            Message.assistant("hello", 1),
            Message.toolResult("t1", "r1", 1),
            Message.assistant("done", 2)
        );
        var result = sanitizer.sanitize(messages);
        assertThat(result).hasSize(5);
        assertThat(result.stream().map(Message::role)).containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT);
    }

    @Test
    void collapsesConsecutiveUserMessages() {
        var messages = List.of(
            Message.user("a"),
            Message.user("b"),
            Message.assistant("ok", 1)
        );
        var result = sanitizer.sanitize(messages);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).contains("a").contains("b");
    }

    @Test
    void insertsPlaceholderUserBeforeAssistantStart() {
        var messages = List.of(
            Message.assistant("hi", 1)
        );
        var result = sanitizer.sanitize(messages);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(1).role()).isEqualTo(Role.ASSISTANT);
    }

    @Test
    void removesTrailingToolResults() {
        var messages = List.of(
            Message.user("x"),
            Message.assistant("a", 1),
            Message.toolResult("t1", "r1", 1)
        );
        var result = sanitizer.sanitize(messages);
        assertThat(result).hasSize(2);
        assertThat(result.get(result.size() - 1).role()).isNotEqualTo(Role.TOOL);
    }

    @Test
    void rejectsEmptyList() {
        assertThatThrownBy(() -> sanitizer.sanitize(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
