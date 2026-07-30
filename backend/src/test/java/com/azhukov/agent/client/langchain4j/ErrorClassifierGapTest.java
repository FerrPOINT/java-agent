package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for P0 gap: Error classification.
 * <p>
 * {@link ErrorClassifier} supports 3 categories: RETRYABLE, PERMANENT, RATE_LIMIT.
 * Tests verify correct classification for known patterns and document gaps where
 * important error categories are misclassified.
 *
 * GAPS documented:
 * - Billing errors ("insufficient credits") → classified as RETRYABLE (should be PERMANENT)
 * - Context overflow ("context length exceeded") → classified as RETRYABLE (should be PERMANENT)
 * - Content policy violations ("content policy violation") → classified as RETRYABLE (should be PERMANENT or have own category)
 * - No specific handling for auth token expiry, quota exceeded, model not found, etc.
 */
class ErrorClassifierGapTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    // ─── RETRYABLE ───

    @Nested
    @DisplayName("RETRYABLE errors")
    class RetryableErrors {

        @Test
        @DisplayName("null exception → RETRYABLE (safe default)")
        void nullException() {
            assertThat(classifier.classify(null)).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("TimeoutException → RETRYABLE")
        void timeoutException() {
            assertThat(classifier.classify(new TimeoutException()))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("message containing 'timeout' → RETRYABLE")
        void messageContainsTimeout() {
            assertThat(classifier.classify(new RuntimeException("Request timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("message containing 'timed out' → RETRYABLE")
        void messageContainsTimedOut() {
            assertThat(classifier.classify(new RuntimeException("Operation timed out")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("message containing 'connection' → RETRYABLE")
        void messageContainsConnection() {
            assertThat(classifier.classify(new RuntimeException("connection failed")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("message containing 'refused' → RETRYABLE")
        void messageContainsRefused() {
            assertThat(classifier.classify(new RuntimeException("connection refused")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("message containing 'reset' → RETRYABLE")
        void messageContainsReset() {
            assertThat(classifier.classify(new RuntimeException("connection reset")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("unknown generic exception → RETRYABLE (safe default)")
        void unknownException() {
            assertThat(classifier.classify(new RuntimeException("something unexpected")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("exception with null message → RETRYABLE (safe default)")
        void nullMessageException() {
            assertThat(classifier.classify(new RuntimeException()))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("empty message exception → RETRYABLE (safe default)")
        void emptyMessageException() {
            assertThat(classifier.classify(new RuntimeException("")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("503 service unavailable → RETRYABLE (default, not explicitly classified)")
        void serviceUnavailable() {
            assertThat(classifier.classify(new RuntimeException("503 service unavailable")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("500 internal server error → RETRYABLE (default, not explicitly classified)")
        void internalServerError() {
            assertThat(classifier.classify(new RuntimeException("500 internal server error")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }
    }

    // ─── PERMANENT ───

    @Nested
    @DisplayName("PERMANENT errors")
    class PermanentErrors {

        @Test
        @DisplayName("IllegalArgumentException → PERMANENT")
        void illegalArgument() {
            assertThat(classifier.classify(new IllegalArgumentException("bad argument")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }

        @Test
        @DisplayName("message containing 'invalid' + 'key' → PERMANENT")
        void invalidKey() {
            assertThat(classifier.classify(new RuntimeException("invalid API key")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }

        @Test
        @DisplayName("message containing 'invalid' + 'api' → PERMANENT")
        void invalidApi() {
            assertThat(classifier.classify(new RuntimeException("invalid api credentials")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }

        @Test
        @DisplayName("message containing 'Invalid' (capitalized) + 'Key' → PERMANENT (case insensitive)")
        void invalidKeyCaseInsensitive() {
            assertThat(classifier.classify(new RuntimeException("Invalid Key format")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }
    }

    // ─── RATE_LIMIT ───

    @Nested
    @DisplayName("RATE_LIMIT errors")
    class RateLimitErrors {

        @Test
        @DisplayName("message containing 'rate limit' → RATE_LIMIT")
        void rateLimit() {
            assertThat(classifier.classify(new RuntimeException("rate limit exceeded")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("message containing '429' → RATE_LIMIT")
        void http429() {
            assertThat(classifier.classify(new RuntimeException("HTTP 429 Too Many Requests")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("message containing 'too many requests' → RATE_LIMIT")
        void tooManyRequests() {
            assertThat(classifier.classify(new RuntimeException("too many requests")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("message containing 'Too Many Requests' (capitalized) → RATE_LIMIT (case insensitive)")
        void tooManyRequestsCapitalized() {
            assertThat(classifier.classify(new RuntimeException("Too Many Requests")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }
    }

    // ─── GAP: Missing categories ───

    @Nested
    @DisplayName("GAP: Misclassified error categories")
    class GapMisclassifiedErrors {

        @Test
        @DisplayName("GAP: 'insufficient credits' (billing) → classified as RETRYABLE, should be PERMANENT")
        void gap_billingError() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("insufficient credits"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT — retrying won't fix a billing issue
        }

        @Test
        @DisplayName("GAP: 'billing quota exceeded' → classified as RETRYABLE, should be PERMANENT")
        void gap_billingQuota() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("billing quota exceeded for this period"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT
        }

        @Test
        @DisplayName("GAP: 'context length exceeded' (context overflow) → classified as RETRYABLE, should be PERMANENT")
        void gap_contextOverflow() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("context length exceeded maximum tokens"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT — retrying with the same context will always fail
        }

        @Test
        @DisplayName("GAP: 'maximum context length' → classified as RETRYABLE, should be PERMANENT")
        void gap_maxContextLength() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("This model's maximum context length is 8192 tokens"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT
        }

        @Test
        @DisplayName("GAP: 'content policy violation' → classified as RETRYABLE, should be PERMANENT")
        void gap_contentPolicy() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content policy violation detected"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT or have its own category
        }

        @Test
        @DisplayName("GAP: 'content filter triggered' → classified as RETRYABLE, should be PERMANENT")
        void gap_contentFilter() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content filter triggered on input"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT
        }

        @Test
        @DisplayName("GAP: 'model not found' → classified as RETRYABLE, should be PERMANENT")
        void gap_modelNotFound() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("model not found: gpt-99"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be PERMANENT — model name won't become valid on retry
        }

        @Test
        @DisplayName("GAP: 'authentication expired' (not 'invalid key') → classified as RETRYABLE")
        void gap_authExpired() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("authentication token expired"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: token expiry might be retriable (refresh token) or permanent
        }

        @Test
        @DisplayName("GAP: 'quota exceeded' (without 'rate limit') → classified as RETRYABLE")
        void gap_quotaExceeded() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("quota exceeded for this organization"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: should be RATE_LIMIT or PERMANENT depending on quota type
        }

        @Test
        @DisplayName("GAP: 'insufficient_quota' → classified as RETRYABLE, should be PERMANENT or RATE_LIMIT")
        void gap_insufficientQuota() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("insufficient_quota"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // GAP: OpenAI returns this for billing quota issues
        }
    }

    // ─── Edge cases ───

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Exception with very long message is classified correctly")
        void longMessage() {
            String longMsg = "rate limit " + "x".repeat(10000);
            assertThat(classifier.classify(new RuntimeException(longMsg)))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("Multiple matching patterns — rate limit takes priority over timeout")
        void rateLimitTakesPriorityOverTimeout() {
            assertThat(classifier.classify(new RuntimeException("rate limit and timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("IllegalArgumentException with timeout message → RETRYABLE (message checked before type)")
        void illegalArgumentWithTimeoutMessage() {
            // timeout check (lines 42-47) comes before IllegalArgumentException (lines 50-52)
            assertThat(classifier.classify(new IllegalArgumentException("request timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("RuntimeException with 'invalid' but no 'key' or 'api' → RETRYABLE (default)")
        void invalidWithoutKeyOrApi() {
            assertThat(classifier.classify(new RuntimeException("invalid request format")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            // "invalid" alone without "key" or "api" doesn't trigger PERMANENT
        }
    }
}