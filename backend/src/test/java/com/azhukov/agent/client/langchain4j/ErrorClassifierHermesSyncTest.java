package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Hermes-sync bug fixes in ErrorClassifier:
 * - h80: connect/DNS failure message matching
 * - h81: empty-response advisory (no compression trigger)
 * - h82: GLM token-limit as context overflow
 */
class ErrorClassifierHermesSyncTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    // ── h80: connect/DNS failures ──

    @Test
    void connectionRefusedClassifiedAsRetryable() {
        Exception e = new RuntimeException("Connection refused");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RETRYABLE, result.type());
        assertTrue(result.hints().retryable());
    }

    @Test
    void unknownHostClassifiedAsRetryable() {
        Exception e = new RuntimeException("Unknown host: api.example.com");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RETRYABLE, result.type());
    }

    @Test
    void networkUnreachableClassifiedAsRetryable() {
        Exception e = new RuntimeException("Network is unreachable");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RETRYABLE, result.type());
    }

    @Test
    void noRouteToHostClassifiedAsRetryable() {
        Exception e = new RuntimeException("No route to host");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RETRYABLE, result.type());
    }

    // ── h81: empty-response advisory ──

    @Test
    void emptyResponseClassifiedAsEmptyResponseAdvisory() {
        Exception e = new RuntimeException("empty response");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.EMPTY_RESPONSE, result.type());
    }

    @Test
    void emptyContentClassifiedAsEmptyResponseAdvisory() {
        Exception e = new RuntimeException("empty content");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.EMPTY_RESPONSE, result.type());
    }

    @Test
    void noContentReturnedClassifiedAsEmptyResponseAdvisory() {
        Exception e = new RuntimeException("no content returned");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.EMPTY_RESPONSE, result.type());
    }

    @Test
    void responseWasEmptyClassifiedAsEmptyResponseAdvisory() {
        Exception e = new RuntimeException("response was empty");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.EMPTY_RESPONSE, result.type());
    }

    @Test
    void emptyResponseAdvisoryDoesNotTriggerCompression() {
        Exception e = new RuntimeException("empty response");
        var result = classifier.classifyWithHints(e);
        assertFalse(result.hints().shouldCompress(), "Empty-response advisory should NOT trigger compression");
        assertFalse(result.hints().retryable(), "Empty-response advisory should NOT retry");
    }

    // ── h82: GLM token-limit as context overflow ──

    @Test
    void glmTokenLimitReachedClassifiedAsContextOverflow() {
        Exception e = new RuntimeException("token limit reached");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
        assertTrue(result.hints().shouldCompress());
    }

    @Test
    void glmMaximumContextLengthClassifiedAsContextOverflow() {
        Exception e = new RuntimeException("maximum context length exceeded");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }

    @Test
    void glmInputTooLongClassifiedAsContextOverflow() {
        Exception e = new RuntimeException("input too long");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }

    // ── advisory recovery hint ──

    @Test
    void advisoryHintsAllFalse() {
        var hints = ErrorClassifier.RecoveryHints.advisory();
        assertFalse(hints.retryable());
        assertFalse(hints.shouldCompress());
        assertFalse(hints.shouldRotateCredential());
        assertFalse(hints.shouldFallback());
    }
}