package com.azhukov.agent.bot.core;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the user-friendly error message conversion (c5: moved to StreamingOrchestrator).
 * Verifies that raw error messages are replaced with contextual, user-friendly text
 * matching Hermes behavior.
 */
class BotMessageProcessorErrorDisplayTest {

    @Test
    void rateLimitError_returnsUserFriendlyMessage() {
        Throwable error = new RuntimeException("Rate limit exceeded: 429 Too Many Requests");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Rate limited by Telegram. Retrying...");
    }

    @Test
    void floodError_returnsUserFriendlyMessage() {
        Throwable error = new RuntimeException("Flood control: retry after 5 seconds");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Rate limited by Telegram. Retrying...");
    }

    @Test
    void error429_returnsRateLimitMessage() {
        Throwable error = new RuntimeException("HTTP 429");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Rate limited by Telegram. Retrying...");
    }

    @Test
    void timeoutException_returnsNetworkMessage() {
        Throwable error = new TimeoutException("Operation timed out");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Network issue. Retrying...");
    }

    @Test
    void socketTimeoutException_returnsNetworkMessage() {
        Throwable error = new SocketTimeoutException("Read timed out");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Network issue. Retrying...");
    }

    @Test
    void connectionError_returnsNetworkMessage() {
        Throwable error = new RuntimeException("Connection refused");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Network issue. Retrying...");
    }

    @Test
    void networkError_returnsNetworkMessage() {
        Throwable error = new RuntimeException("Network unreachable");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Network issue. Retrying...");
    }

    @Test
    void authError_returnsConfigurationMessage() {
        Throwable error = new RuntimeException("Unauthorized: invalid API key");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Configuration issue. Contact admin.");
    }

    @Test
    void forbiddenError_returnsConfigurationMessage() {
        Throwable error = new RuntimeException("403 Forbidden: access denied");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Configuration issue. Contact admin.");
    }

    @Test
    void genericError_returnsGenericMessage() {
        Throwable error = new RuntimeException("Some unexpected error");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Temporary issue. Please try again.");
    }

    @Test
    void nullError_returnsGenericMessage() {
        String result = StreamingOrchestrator.toUserFriendlyError(null);
        assertThat(result).isEqualTo("Temporary issue. Please try again.");
    }

    @Test
    void nullMessageError_returnsGenericMessage() {
        Throwable error = new RuntimeException();
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).isEqualTo("Temporary issue. Please try again.");
    }

    @Test
    void errorMessagesDoNotContainRawExceptionText() {
        // Verify that user-friendly messages never leak raw exception details
        Throwable error = new RuntimeException("java.lang.RuntimeException: secret stack trace with passwords");
        String result = StreamingOrchestrator.toUserFriendlyError(error);
        assertThat(result).doesNotContain("secret");
        assertThat(result).doesNotContain("password");
        assertThat(result).doesNotContain("stack trace");
    }
}