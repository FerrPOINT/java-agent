package com.azhukov.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserInputSanitizerTest {

    @Test
    void removesControlCharactersButKeepsWhitespace() {
        UserInputSanitizer s = new UserInputSanitizer();
        assertThat(s.sanitize("hi\u0000\n\t\r")).isEqualTo("hi\n\t\r");
    }

    @Test
    void removesSurrogates() {
        UserInputSanitizer s = new UserInputSanitizer();
        assertThat(s.sanitize("x\udcffy")).isEqualTo("xy");
    }

    @Test
    void returnsEmptyForNull() {
        UserInputSanitizer s = new UserInputSanitizer();
        assertThat(s.sanitize(null)).isEmpty();
    }

    @Test
    void truncatesLongInput() {
        UserInputSanitizer s = new UserInputSanitizer();
        String longText = "x".repeat(200_001);
        assertThat(s.sanitize(longText)).endsWith("\n[input truncated]");
    }
}
