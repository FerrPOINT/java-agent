package com.azhukov.agent.security;

import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSanitizerTest {

    private MessageSanitizer sanitizer() {
        return new MessageSanitizer(new SecretRedactor(new com.azhukov.agent.config.AgentProperties()));
    }

    @Test
    void removesControlCharacters() {
        MessageSanitizer s = sanitizer();
        Message m = s.sanitize(Message.user("hello\u0000\u0001world"));
        assertThat(m.content()).isEqualTo("helloworld");
    }

    @Test
    void keepsNewlinesTabsAndCarriageReturns() {
        MessageSanitizer s = sanitizer();
        Message m = s.sanitize(Message.user("line1\nline2\tcol\r"));
        assertThat(m.content()).isEqualTo("line1\nline2\tcol\r");
    }

    @Test
    void removesSurrogates() {
        MessageSanitizer s = sanitizer();
        Message m = s.sanitize(Message.user("abc\ud800def"));
        assertThat(m.content()).isEqualTo("abcdef");
    }

    @Test
    void truncatesVeryLongContent() {
        MessageSanitizer s = sanitizer();
        String longText = "a".repeat(1_000_001);
        Message m = s.sanitize(Message.user(longText));
        assertThat(m.content()).endsWith("\n[truncated]");
        assertThat(m.content().length()).isEqualTo(1_000_000 + "\n[truncated]".length());
    }

    @Test
    void sanitizesListOfMessages() {
        MessageSanitizer s = sanitizer();
        List<Message> result = s.sanitize(List.of(Message.user("a\u0000"), Message.assistant("b", 1)));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).content()).isEqualTo("a");
        assertThat(result.get(1).content()).isEqualTo("b");
    }

    @Test
    void handlesNullMessage() {
        MessageSanitizer s = sanitizer();
        assertThat(s.sanitize((Message) null)).isNull();
    }
}
