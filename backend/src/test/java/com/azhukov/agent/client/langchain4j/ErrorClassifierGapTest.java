package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Error classification.
 * <p>
 * {@link ErrorClassifier} supports 6 categories: RETRYABLE, PERMANENT, RATE_LIMIT,
 * BILLING, CONTEXT_OVERFLOW, CONTENT_POLICY.
 * Tests verify correct classification for known patterns.
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
        @DisplayName("503 service unavailable → RATE_LIMIT (model overloaded, retry with backoff)")
        void serviceUnavailable() {
            assertThat(classifier.classify(new RuntimeException("503 service unavailable")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
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

        @Test
        @DisplayName("'model temporarily overloaded' → RATE_LIMIT")
        void modelOverloaded() {
            assertThat(classifier.classify(new RuntimeException("model is temporarily overloaded")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("'overloaded' → RATE_LIMIT")
        void overloaded() {
            assertThat(classifier.classify(new RuntimeException("Server overloaded, please try again")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("'temporarily unavailable' → RATE_LIMIT")
        void temporarilyUnavailable() {
            assertThat(classifier.classify(new RuntimeException("Service temporarily unavailable")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }

        @Test
        @DisplayName("'please try again later' → RATE_LIMIT")
        void tryAgainLater() {
            assertThat(classifier.classify(new RuntimeException("Please try again later")))
                .isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
        }
    }

    // ─── BILLING ───

    @Nested
    @DisplayName("BILLING errors (permanent, no retry)")
    class BillingErrors {

        @Test
        @DisplayName("'insufficient credits' → BILLING")
        void billingInsufficientCredits() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("insufficient credits"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'billing quota exceeded' → BILLING")
        void billingQuotaExceeded() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("billing quota exceeded for this period"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'insufficient_quota' → BILLING")
        void insufficientQuota() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("insufficient_quota"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'insufficient balance' → BILLING")
        void insufficientBalance() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("insufficient balance on account"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'credit balance' → BILLING")
        void creditBalance() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("credit balance is zero"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'payment required' → BILLING")
        void paymentRequired() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("payment required to continue"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'quota exceeded' → BILLING")
        void quotaExceeded() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("quota exceeded for this organization"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }
    }

    // ─── CONTEXT_OVERFLOW ───

    @Nested
    @DisplayName("CONTEXT_OVERFLOW errors (permanent, no retry)")
    class ContextOverflowErrors {

        @Test
        @DisplayName("'context length exceeded' → CONTEXT_OVERFLOW")
        void contextLengthExceeded() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("context length exceeded maximum tokens"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
        }

        @Test
        @DisplayName("'maximum context length' → CONTEXT_OVERFLOW")
        void maxContextLength() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("This model's maximum context length is 8192 tokens"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
        }

        @Test
        @DisplayName("'context window' → CONTEXT_OVERFLOW")
        void contextWindow() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("exceeds context window of 4096 tokens"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
        }

        @Test
        @DisplayName("'token limit exceeded' → CONTEXT_OVERFLOW")
        void tokenLimitExceeded() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("token limit exceeded"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
        }

        @Test
        @DisplayName("'context_length_exceeded' → CONTEXT_OVERFLOW")
        void contextLengthExceededSnakeCase() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("context_length_exceeded"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
        }
    }

    // ─── CONTENT_POLICY ───

    @Nested
    @DisplayName("CONTENT_POLICY errors (permanent, no retry)")
    class ContentPolicyErrors {

        @Test
        @DisplayName("'content policy violation' → CONTENT_POLICY")
        void contentPolicyViolation() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content policy violation detected"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'content filter triggered' → CONTENT_POLICY")
        void contentFilterTriggered() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content filter triggered on input"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'content management' → CONTENT_POLICY")
        void contentManagement() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content management policy blocked this"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'safety' → CONTENT_POLICY")
        void safety() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("safety filter triggered"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'harmful content' → CONTENT_POLICY")
        void harmfulContent() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("harmful content detected"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'prohibited content' → CONTENT_POLICY")
        void prohibitedContent() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("prohibited content in request"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
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
            // timeout check comes before IllegalArgumentException
            assertThat(classifier.classify(new IllegalArgumentException("request timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("RuntimeException with 'invalid' but no 'key' or 'api' → RETRYABLE (default)")
        void invalidWithoutKeyOrApi() {
            assertThat(classifier.classify(new RuntimeException("invalid request format")))
                .isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
        }

        @Test
        @DisplayName("Billing takes priority over rate limit when both patterns match")
        void billingTakesPriorityOverRateLimit() {
            assertThat(classifier.classify(new RuntimeException("quota exceeded rate limit")))
                .isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }
    }
}