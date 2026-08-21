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

    // ── usage-limit disambiguation (Hermes _USAGE_LIMIT_PATTERNS / _classify_by_message) ──

    @Test
    void ollamaWeeklyUsageLimitClassifiedAsBilling() {
        // Real production shape (2026-08-20 22:38): Ollama Cloud returns
        // {"error":{"message":"you (user) have reached your weekly usage limit,
        // add extra usage: https://ollama.com/settings ...","type":"api_error"}}
        // — no HTTP status in the message, no transient signal → billing,
        // non-retryable. Previously misclassified RETRYABLE → 4 paid retries.
        Exception e = new RuntimeException(
            "{\"error\":{\"message\":\"you (azhukovjava) have reached your weekly usage limit, "
                + "add extra usage: https://ollama.com/settings (ref: 2c58fa4c)\",\"type\":\"api_error\"}}");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.BILLING, result.type());
        assertFalse(result.hints().retryable(), "billing must not burn retries");
        assertTrue(result.hints().shouldFallback());
    }

    @Test
    void usageLimitWithTransientSignalClassifiedAsRateLimit() {
        // "Usage limit, try again in 5 minutes" — periodic quota, not billing
        Exception e = new RuntimeException("Usage limit exceeded, please try again in 5 minutes");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RATE_LIMIT, result.type());
        assertTrue(result.hints().retryable());
    }

    @Test
    void usageLimitResetsAtClassifiedAsRateLimit() {
        Exception e = new RuntimeException("You have hit your usage limit; limit resets at 12:00 UTC");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RATE_LIMIT, result.type());
    }

    @Test
    void sessionUsageLimitClassifiedAsBilling() {
        // Ollama Cloud session (hourly) limit — same shape, also no transient signal
        Exception e = new RuntimeException(
            "you (user) have reached your session usage limit, add extra usage: https://ollama.com/settings");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.BILLING, result.type());
    }

    @Test
    void rateLimitExceededStillRateLimit() {
        // Regression guard: "rate limit exceeded" contains "limit exceeded"
        // but must keep RATE_LIMIT semantics (429 path, not usage-limit)
        Exception e = new RuntimeException("Rate limit exceeded: 429 Too Many Requests");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.RATE_LIMIT, result.type());
    }

    @Test
    void glmTokenLimitStillContextOverflow() {
        // Regression guard: "token limit reached/exceeded" is GLM's context
        // overflow (h82), not usage-limit billing
        Exception e = new RuntimeException("token limit exceeded");
        var result = classifier.classifyWithHints(e);
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }

    @Test
    void malformedHistoryLitellmWrapper_classifiedAsContextOverflow() {
        // Production 2026-08-21 15:50: litellm wrapped Gemini's request-shape
        // rejection into APIConnectionError/ChatgptException — previously
        // matched AUTH and burned 4 retries on the same broken history.
        String err = "litellm.APIConnectionError: AuthenticationError: ChatgptException - "
            + "Missing corresponding tool call for tool response message. Received - "
            + "message={'role': 'tool', 'tool_call_id': 'call_873e...', 'content': 'data:image/png;base64,...'}";
        var result = classifier.classifyWithHints(new RuntimeException(err));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }

    @Test
    void deepseekToolCallsMustBeFollowed_classifiedAsContextOverflow() {
        String err = "Error code: 400 - an assistant message with 'tool_calls' must be followed by tool messages";
        var result = classifier.classifyWithHints(new RuntimeException(err));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }

    @Test
    void geminiFunctionCallTurnOrdering_classifiedAsContextOverflow() {
        // Production 2026-08-21 16:50:03 — litellm concatenated Gemini 400
        // ("Please ensure that function call turn comes immediately after a
        // user turn...") with dead-fallback AuthenticationError noise; the
        // whole envelope matched AUTH. Primary cause is history shape.
        String err = "b'{\n  \"error\": {\n    \"code\": 400,\n    \"message\": \"Please ensure that "
            + "function call turn comes immediately after a user turn or after a function response turn.\",\n"
            + "    \"status\": \"INVALID_ARGUMENT\"\n  }\n}\n'. Received Model Group=app-test\n"
            + "Available Model Group Fallbacks=['ollama-kimi-k2.6', 'zai-glm-5', 'chatgpt-5.6-luna']\n"
            + "Error doing the fallback: litellm.AuthenticationError: AuthenticationError: ChatgptException - "
            + "Encountered invalidated oauth token for user, failing request token_revoked 401";
        var result = classifier.classifyWithHints(new RuntimeException(err));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, result.type());
    }
}