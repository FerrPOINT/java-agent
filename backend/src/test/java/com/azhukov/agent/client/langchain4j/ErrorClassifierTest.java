package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void classify_rateLimitMessage_returnsRateLimit() {
        ErrorClassifier.ErrorType type = classifier.classify(
            new RuntimeException("Rate limit exceeded: 429 Too Many Requests"));
        assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
    }

    @Test
    void classify_timeoutException_returnsTimeout() {
        ErrorClassifier.ErrorType type = classifier.classify(new TimeoutException("Operation timed out"));
        assertThat(type).isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
    }

    @Test
    void classify_invalidApiKey_returnsAuth() {
        ErrorClassifier.ErrorType type = classifier.classify(
            new RuntimeException("Invalid API key provided"));
        assertThat(type).isEqualTo(ErrorClassifier.ErrorType.AUTH);
    }

    @Test
    void classify_connectionRefused_returnsRetryable() {
        ErrorClassifier.ErrorType type = classifier.classify(
            new RuntimeException("Connection refused"));
        assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
    }

    @Test
    void classify_unknownException_returnsRetryable() {
        ErrorClassifier.ErrorType type = classifier.classify(
            new RuntimeException("Something went wrong"));
        assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
    }
}