package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Error classification.
 * <p>
 * {@link ErrorClassifier} supports 22 categories: RETRYABLE, PERMANENT, RATE_LIMIT,
 * BILLING, CONTEXT_OVERFLOW, CONTENT_POLICY, AUTH, AUTH_PERMANENT, OVERLOADED,
 * SERVER_ERROR, TIMEOUT, MODEL_NOT_FOUND, FORMAT_ERROR,
 * PAYLOAD_TOO_LARGE, IMAGE_TOO_LARGE, PROVIDER_POLICY_BLOCKED,
 * INVALID_ENCRYPTED_CONTENT, THINKING_SIGNATURE, LONG_CONTEXT_TIER,
 * LLAMA_CPP_GRAMMAR, MULTIMODAL_TOOL_CONTENT.
 * Tests verify correct classification for known patterns and recovery hints.
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
    }

    // ─── TIMEOUT ───

    @Nested
    @DisplayName("TIMEOUT errors")
    class TimeoutErrors {

        @Test
        @DisplayName("TimeoutException → TIMEOUT")
        void timeoutException() {
            assertThat(classifier.classify(new TimeoutException()))
                .isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
        }

        @Test
        @DisplayName("message containing 'timeout' → TIMEOUT")
        void messageContainsTimeout() {
            assertThat(classifier.classify(new RuntimeException("Request timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
        }

        @Test
        @DisplayName("message containing 'timed out' → TIMEOUT")
        void messageContainsTimedOut() {
            assertThat(classifier.classify(new RuntimeException("Operation timed out")))
                .isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
        }

        @Test
        @DisplayName("IllegalArgumentException with timeout message → TIMEOUT (message checked before type)")
        void illegalArgumentWithTimeoutMessage() {
            // timeout check comes before IllegalArgumentException
            assertThat(classifier.classify(new IllegalArgumentException("request timeout")))
                .isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
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

    // ─── OVERLOADED ───

    @Nested
    @DisplayName("OVERLOADED errors")
    class OverloadedErrors {

        @Test
        @DisplayName("'overloaded' → OVERLOADED")
        void overloaded() {
            assertThat(classifier.classify(new RuntimeException("Server overloaded, please try again")))
                .isEqualTo(ErrorClassifier.ErrorType.OVERLOADED);
        }

        @Test
        @DisplayName("'model temporarily overloaded' → OVERLOADED")
        void modelOverloaded() {
            assertThat(classifier.classify(new RuntimeException("model is temporarily overloaded")))
                .isEqualTo(ErrorClassifier.ErrorType.OVERLOADED);
        }

        @Test
        @DisplayName("'529' → OVERLOADED (Anthropic-specific)")
        void http529() {
            assertThat(classifier.classify(new RuntimeException("529 overloaded by upstream provider")))
                .isEqualTo(ErrorClassifier.ErrorType.OVERLOADED);
        }
    }

    // ─── SERVER_ERROR ───

    @Nested
    @DisplayName("SERVER_ERROR errors")
    class ServerErrorErrors {

        @Test
        @DisplayName("503 service unavailable → SERVER_ERROR")
        void serviceUnavailable() {
            assertThat(classifier.classify(new RuntimeException("503 service unavailable")))
                .isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
        }

        @Test
        @DisplayName("500 internal server error → SERVER_ERROR")
        void internalServerError() {
            assertThat(classifier.classify(new RuntimeException("500 internal server error")))
                .isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
        }

        @Test
        @DisplayName("'temporarily unavailable' → SERVER_ERROR")
        void temporarilyUnavailable() {
            assertThat(classifier.classify(new RuntimeException("Service temporarily unavailable")))
                .isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
        }

        @Test
        @DisplayName("'please try again later' → SERVER_ERROR")
        void tryAgainLater() {
            assertThat(classifier.classify(new RuntimeException("Please try again later")))
                .isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
        }

        @Test
        @DisplayName("'server error' → SERVER_ERROR")
        void serverError() {
            assertThat(classifier.classify(new RuntimeException("upstream server error")))
                .isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
        }
    }

    // ─── AUTH ───

    @Nested
    @DisplayName("AUTH errors (401 — token expired or invalid, may need rotation)")
    class AuthErrors {

        @Test
        @DisplayName("'401' → AUTH")
        void http401() {
            assertThat(classifier.classify(new RuntimeException("401 Unauthorized")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }

        @Test
        @DisplayName("'unauthorized' → AUTH")
        void unauthorized() {
            assertThat(classifier.classify(new RuntimeException("unauthorized access")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }

        @Test
        @DisplayName("'invalid api key' → AUTH (matches before PERMANENT invalid+key check)")
        void invalidApiKey() {
            assertThat(classifier.classify(new RuntimeException("invalid API key")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }

        @Test
        @DisplayName("'invalid_api_key' → AUTH")
        void invalidApiKeySnakeCase() {
            assertThat(classifier.classify(new RuntimeException("invalid_api_key")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }

        @Test
        @DisplayName("'authentication failed' → AUTH")
        void authenticationFailed() {
            assertThat(classifier.classify(new RuntimeException("authentication failed for user")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }
    }

    // ─── AUTH_PERMANENT ───

    @Nested
    @DisplayName("AUTH_PERMANENT errors (403 — key revoked, forbidden)")
    class AuthPermanentErrors {

        @Test
        @DisplayName("'403' → AUTH_PERMANENT")
        void http403() {
            assertThat(classifier.classify(new RuntimeException("403 Forbidden")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH_PERMANENT);
        }

        @Test
        @DisplayName("'forbidden' → AUTH_PERMANENT")
        void forbidden() {
            assertThat(classifier.classify(new RuntimeException("access forbidden")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH_PERMANENT);
        }

        @Test
        @DisplayName("'access denied' → AUTH_PERMANENT")
        void accessDenied() {
            assertThat(classifier.classify(new RuntimeException("access denied to resource")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH_PERMANENT);
        }

        @Test
        @DisplayName("'permission denied' → AUTH_PERMANENT")
        void permissionDenied() {
            assertThat(classifier.classify(new RuntimeException("permission denied for this operation")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH_PERMANENT);
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
        @DisplayName("message containing 'invalid' + 'api' (but not 'invalid api key') → PERMANENT")
        void invalidApi() {
            assertThat(classifier.classify(new RuntimeException("invalid api credentials")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }

        @Test
        @DisplayName("message containing 'Invalid' (capitalized) + 'Key' → PERMANENT (case insensitive, no 'api' so no AUTH match)")
        void invalidKeyCaseInsensitive() {
            assertThat(classifier.classify(new RuntimeException("Invalid Key format")))
                .isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
        }
    }

    // ─── MODEL_NOT_FOUND ───

    @Nested
    @DisplayName("MODEL_NOT_FOUND errors (404 — model name wrong or not available)")
    class ModelNotFoundErrors {

        @Test
        @DisplayName("'404' → MODEL_NOT_FOUND")
        void http404() {
            assertThat(classifier.classify(new RuntimeException("404 Not Found")))
                .isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
        }

        @Test
        @DisplayName("'model not found' → MODEL_NOT_FOUND")
        void modelNotFound() {
            assertThat(classifier.classify(new RuntimeException("model not found: gpt-5")))
                .isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
        }

        @Test
        @DisplayName("'model_not_found' → MODEL_NOT_FOUND")
        void modelNotFoundSnakeCase() {
            assertThat(classifier.classify(new RuntimeException("model_not_found")))
                .isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
        }

        @Test
        @DisplayName("'no such model' → MODEL_NOT_FOUND")
        void noSuchModel() {
            assertThat(classifier.classify(new RuntimeException("no such model available")))
                .isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
        }

        @Test
        @DisplayName("'does not exist' → MODEL_NOT_FOUND")
        void doesNotExist() {
            assertThat(classifier.classify(new RuntimeException("model 'gpt-99' does not exist")))
                .isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
        }
    }

    // ─── FORMAT_ERROR ───

    @Nested
    @DisplayName("FORMAT_ERROR errors (422 — request format rejected)")
    class FormatErrorErrors {

        @Test
        @DisplayName("'422' → FORMAT_ERROR")
        void http422() {
            assertThat(classifier.classify(new RuntimeException("422 Unprocessable Entity")))
                .isEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
        }

        @Test
        @DisplayName("'unprocessable' → FORMAT_ERROR")
        void unprocessable() {
            assertThat(classifier.classify(new RuntimeException("request is unprocessable")))
                .isEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
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

        // New billing patterns (Part B)
        @Test
        @DisplayName("'402' status → BILLING")
        void http402() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("402 Payment Required"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'billing' in message → BILLING")
        void billingKeyword() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("billing error: account suspended"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'credits exhausted' → BILLING")
        void creditsExhausted() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("credits exhausted"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }

        @Test
        @DisplayName("'account is deactivated' → BILLING")
        void accountDeactivated() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("account is deactivated"));
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

        // New content policy patterns (Part B — from Hermes _CONTENT_POLICY_BLOCKED_PATTERNS)
        @Test
        @DisplayName("'flagged for possible cybersecurity risk' → CONTENT_POLICY")
        void cybersecurityRisk() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("flagged for possible cybersecurity risk"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'violates our usage policies' → CONTENT_POLICY")
        void violatesUsagePolicies() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("violates our usage policies"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'prompt was flagged by our safety' → CONTENT_POLICY")
        void promptFlaggedBySafety() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("prompt was flagged by our safety system"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("'content_filter' → CONTENT_POLICY")
        void contentFilterUnderscore() {
            ErrorClassifier.ErrorType type = classifier.classify(
                new RuntimeException("content_filter"));
            assertThat(type).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }
    }

    // ─── PAYLOAD_TOO_LARGE (Part B) ──

    @Nested
    @DisplayName("PAYLOAD_TOO_LARGE errors (413 — compress payload)")
    class PayloadTooLargeErrors {

        @Test
        @DisplayName("'413' → PAYLOAD_TOO_LARGE")
        void http413() {
            assertThat(classifier.classify(new RuntimeException("413 Request Entity Too Large")))
                .isEqualTo(ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("'request entity too large' → PAYLOAD_TOO_LARGE")
        void requestEntityTooLarge() {
            assertThat(classifier.classify(new RuntimeException("request entity too large")))
                .isEqualTo(ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("'payload too large' → PAYLOAD_TOO_LARGE")
        void payloadTooLarge() {
            assertThat(classifier.classify(new RuntimeException("payload too large")))
                .isEqualTo(ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("'error code: 413' → PAYLOAD_TOO_LARGE")
        void errorCode413() {
            assertThat(classifier.classify(new RuntimeException("error code: 413")))
                .isEqualTo(ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE);
        }
    }

    // ─── IMAGE_TOO_LARGE (Part B) ──

    @Nested
    @DisplayName("IMAGE_TOO_LARGE errors (413/400 with image context)")
    class ImageTooLargeErrors {

        @Test
        @DisplayName("'image exceeds 5 MB maximum' → IMAGE_TOO_LARGE")
        void imageExceeds() {
            assertThat(classifier.classify(new RuntimeException("image exceeds 5 MB maximum")))
                .isEqualTo(ErrorClassifier.ErrorType.IMAGE_TOO_LARGE);
        }

        @Test
        @DisplayName("'image too large' → IMAGE_TOO_LARGE")
        void imageTooLarge() {
            assertThat(classifier.classify(new RuntimeException("image too large for provider")))
                .isEqualTo(ErrorClassifier.ErrorType.IMAGE_TOO_LARGE);
        }

        @Test
        @DisplayName("'image_too_large' → IMAGE_TOO_LARGE")
        void imageTooLargeSnakeCase() {
            assertThat(classifier.classify(new RuntimeException("image_too_large")))
                .isEqualTo(ErrorClassifier.ErrorType.IMAGE_TOO_LARGE);
        }

        @Test
        @DisplayName("'image dimensions exceed max allowed size' → IMAGE_TOO_LARGE")
        void imageDimensionsExceed() {
            assertThat(classifier.classify(new RuntimeException("image dimensions exceed max allowed size")))
                .isEqualTo(ErrorClassifier.ErrorType.IMAGE_TOO_LARGE);
        }
    }

    // ─── PROVIDER_POLICY_BLOCKED (Part B) ──

    @Nested
    @DisplayName("PROVIDER_POLICY_BLOCKED errors (403/404 — aggregator policy block)")
    class ProviderPolicyBlockedErrors {

        @Test
        @DisplayName("'no endpoints available matching your guardrail' → PROVIDER_POLICY_BLOCKED")
        void noEndpointsGuardrail() {
            assertThat(classifier.classify(
                new RuntimeException("No endpoints available matching your guardrail restrictions")))
                .isEqualTo(ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED);
        }

        @Test
        @DisplayName("'no endpoints available matching your data policy' → PROVIDER_POLICY_BLOCKED")
        void noEndpointsDataPolicy() {
            assertThat(classifier.classify(
                new RuntimeException("no endpoints available matching your data policy")))
                .isEqualTo(ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED);
        }

        @Test
        @DisplayName("'no endpoints found matching your data policy' → PROVIDER_POLICY_BLOCKED")
        void noEndpointsFoundDataPolicy() {
            assertThat(classifier.classify(
                new RuntimeException("no endpoints found matching your data policy")))
                .isEqualTo(ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED);
        }
    }

    // ─── INVALID_ENCRYPTED_CONTENT (Part B) ──

    @Nested
    @DisplayName("INVALID_ENCRYPTED_CONTENT errors (400 with encrypted_content)")
    class InvalidEncryptedContentErrors {

        @Test
        @DisplayName("'400 encrypted_content' → INVALID_ENCRYPTED_CONTENT")
        void encryptedContent() {
            assertThat(classifier.classify(
                new RuntimeException("400 invalid encrypted_content in request")))
                .isEqualTo(ErrorClassifier.ErrorType.INVALID_ENCRYPTED_CONTENT);
        }

        @Test
        @DisplayName("'400 invalid encrypted content' → INVALID_ENCRYPTED_CONTENT")
        void invalidEncrypted() {
            assertThat(classifier.classify(
                new RuntimeException("400 invalid encrypted content blob")))
                .isEqualTo(ErrorClassifier.ErrorType.INVALID_ENCRYPTED_CONTENT);
        }
    }

    // ─── THINKING_SIGNATURE (Part B) ──

    @Nested
    @DisplayName("THINKING_SIGNATURE errors (400 with thinking/sig)")
    class ThinkingSignatureErrors {

        @Test
        @DisplayName("'400 thinking signature' → THINKING_SIGNATURE")
        void thinkingSignature() {
            assertThat(classifier.classify(
                new RuntimeException("400 thinking block signature is invalid")))
                .isEqualTo(ErrorClassifier.ErrorType.THINKING_SIGNATURE);
        }

        @Test
        @DisplayName("'400 thinking cannot be modified' → THINKING_SIGNATURE")
        void thinkingCannotBeModified() {
            assertThat(classifier.classify(
                new RuntimeException("400 thinking blocks in the latest assistant message cannot be modified")))
                .isEqualTo(ErrorClassifier.ErrorType.THINKING_SIGNATURE);
        }

        @Test
        @DisplayName("'400 thinking must remain as they were' → THINKING_SIGNATURE")
        void thinkingMustRemain() {
            assertThat(classifier.classify(
                new RuntimeException("400 thinking blocks must remain as they were in the original response")))
                .isEqualTo(ErrorClassifier.ErrorType.THINKING_SIGNATURE);
        }
    }

    // ─── LONG_CONTEXT_TIER (Part B) ──

    @Nested
    @DisplayName("LONG_CONTEXT_TIER errors (429 with extra usage / long context)")
    class LongContextTierErrors {

        @Test
        @DisplayName("'429 extra usage long context' → LONG_CONTEXT_TIER")
        void extraUsageLongContext() {
            assertThat(classifier.classify(
                new RuntimeException("429 extra usage tier for long context")))
                .isEqualTo(ErrorClassifier.ErrorType.LONG_CONTEXT_TIER);
        }

        @Test
        @DisplayName("'400 long context beta not yet available' → LONG_CONTEXT_TIER")
        void longContextBetaForbidden() {
            assertThat(classifier.classify(
                new RuntimeException("400 The long context beta is not yet available for this subscription")))
                .isEqualTo(ErrorClassifier.ErrorType.LONG_CONTEXT_TIER);
        }
    }

    // ─── LLAMA_CPP_GRAMMAR (Part B) ──

    @Nested
    @DisplayName("LLAMA_CPP_GRAMMAR errors (400 with grammar pattern)")
    class LlamaCppGrammarErrors {

        @Test
        @DisplayName("'400 error parsing grammar' → LLAMA_CPP_GRAMMAR")
        void errorParsingGrammar() {
            assertThat(classifier.classify(
                new RuntimeException("400 error parsing grammar for tool schema")))
                .isEqualTo(ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR);
        }

        @Test
        @DisplayName("'400 json-schema-to-grammar' → LLAMA_CPP_GRAMMAR")
        void jsonSchemaToGrammar() {
            assertThat(classifier.classify(
                new RuntimeException("400 json-schema-to-grammar conversion failed")))
                .isEqualTo(ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR);
        }

        @Test
        @DisplayName("'400 unable to generate parser template' → LLAMA_CPP_GRAMMAR")
        void unableToGenerateParser() {
            assertThat(classifier.classify(
                new RuntimeException("400 unable to generate parser from template")))
                .isEqualTo(ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR);
        }
    }

    // ─── MULTIMODAL_TOOL_CONTENT (Part B) ──

    @Nested
    @DisplayName("MULTIMODAL_TOOL_CONTENT errors (400 — list-type tool content rejected)")
    class MultimodalToolContentErrors {

        @Test
        @DisplayName("'text is not set' → MULTIMODAL_TOOL_CONTENT")
        void textIsNotSet() {
            assertThat(classifier.classify(
                new RuntimeException("Param Incorrect: text is not set")))
                .isEqualTo(ErrorClassifier.ErrorType.MULTIMODAL_TOOL_CONTENT);
        }

        @Test
        @DisplayName("'tool message content must be a string' → MULTIMODAL_TOOL_CONTENT")
        void toolMessageMustBeString() {
            assertThat(classifier.classify(
                new RuntimeException("tool message content must be a string")))
                .isEqualTo(ErrorClassifier.ErrorType.MULTIMODAL_TOOL_CONTENT);
        }

        @Test
        @DisplayName("'expected string, got list' → MULTIMODAL_TOOL_CONTENT")
        void expectedStringGotList() {
            assertThat(classifier.classify(
                new RuntimeException("expected string, got list")))
                .isEqualTo(ErrorClassifier.ErrorType.MULTIMODAL_TOOL_CONTENT);
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

        @Test
        @DisplayName("AUTH takes priority over PERMANENT for 'invalid api key'")
        void authTakesPriorityOverPermanent() {
            assertThat(classifier.classify(new RuntimeException("invalid api key")))
                .isEqualTo(ErrorClassifier.ErrorType.AUTH);
        }

        @Test
        @DisplayName("OVERLOADED takes priority over SERVER_ERROR for 'overloaded' + 'please try again later'")
        void overloadedTakesPriorityOverServerError() {
            assertThat(classifier.classify(new RuntimeException("Server overloaded, please try again later")))
                .isEqualTo(ErrorClassifier.ErrorType.OVERLOADED);
        }

        @Test
        @DisplayName("Content policy takes priority over other 400 errors")
        void contentPolicyTakesPriority() {
            assertThat(classifier.classify(
                new RuntimeException("400 content_filter triggered")))
                .isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
        }

        @Test
        @DisplayName("Thinking signature takes priority over generic 400 format error")
        void thinkingSignaturePriority() {
            assertThat(classifier.classify(
                new RuntimeException("400 thinking block signature is invalid")))
                .isEqualTo(ErrorClassifier.ErrorType.THINKING_SIGNATURE);
        }

        @Test
        @DisplayName("403 with 'key limit exceeded' → BILLING (not AUTH_PERMANENT)")
        void http403KeyLimitExceededIsBilling() {
            assertThat(classifier.classify(
                new RuntimeException("403 key limit exceeded")))
                .isEqualTo(ErrorClassifier.ErrorType.BILLING);
        }
    }

    // ─── classifyWithHints ───

    @Nested
    @DisplayName("classifyWithHints — recovery hints")
    class ClassifyWithHintsTests {

        @Test
        @DisplayName("AUTH → rotateAndRetry hints")
        void authHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("401 Unauthorized"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.AUTH);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldRotateCredential()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("AUTH_PERMANENT → fallback hints")
        void authPermanentHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("403 Forbidden"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.AUTH_PERMANENT);
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldFallback()).isTrue();
        }

        @Test
        @DisplayName("MODEL_NOT_FOUND → switchModel hints")
        void modelNotFoundHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("404 Not Found"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.MODEL_NOT_FOUND);
            assertThat(result.hints().shouldFallback()).isTrue();
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
        }

        @Test
        @DisplayName("FORMAT_ERROR → noRetry hints")
        void formatErrorHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("422 Unprocessable Entity"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("OVERLOADED → canRetry hints")
        void overloadedHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("Server overloaded"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.OVERLOADED);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("SERVER_ERROR → canRetry hints")
        void serverErrorHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("500 internal server error"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.SERVER_ERROR);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("TIMEOUT → canRetry hints")
        void timeoutHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new TimeoutException());
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.TIMEOUT);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("RATE_LIMIT → canRetry hints")
        void rateLimitHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("429 Too Many Requests"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("BILLING → rotateAndFallback hints")
        void billingHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("insufficient credits"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.BILLING);
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isTrue();
            assertThat(result.hints().shouldFallback()).isTrue();
        }

        @Test
        @DisplayName("CONTEXT_OVERFLOW → compressAndRetry hints")
        void contextOverflowHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("context length exceeded"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isTrue();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("CONTENT_POLICY → fallback hints")
        void contentPolicyHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("content policy violation"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.CONTENT_POLICY);
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isTrue();
        }

        @Test
        @DisplayName("PERMANENT → noRetry hints")
        void permanentHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new IllegalArgumentException("bad argument"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.PERMANENT);
            assertThat(result.hints().retryable()).isFalse();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("RETRYABLE (default) → canRetry hints")
        void retryableHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("something unexpected"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        @Test
        @DisplayName("null exception → RETRYABLE with canRetry hints")
        void nullExceptionHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(null);
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.RETRYABLE);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isFalse();
            assertThat(result.hints().shouldRotateCredential()).isFalse();
            assertThat(result.hints().shouldFallback()).isFalse();
        }

        // New hints tests (Part B)
        @Test
        @DisplayName("PAYLOAD_TOO_LARGE → compressAndRetry hints")
        void payloadTooLargeHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("413 Request Entity Too Large"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.PAYLOAD_TOO_LARGE);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isTrue();
        }

        @Test
        @DisplayName("IMAGE_TOO_LARGE → compressAndRetry hints")
        void imageTooLargeHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("image exceeds 5 MB maximum"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.IMAGE_TOO_LARGE);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isTrue();
        }

        @Test
        @DisplayName("PROVIDER_POLICY_BLOCKED → noRetry hints")
        void providerPolicyBlockedHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("no endpoints available matching your guardrail restrictions"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.PROVIDER_POLICY_BLOCKED);
            assertThat(result.hints().retryable()).isFalse();
        }

        @Test
        @DisplayName("THINKING_SIGNATURE → canRetry hints")
        void thinkingSignatureHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("400 thinking block signature is invalid"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.THINKING_SIGNATURE);
            assertThat(result.hints().retryable()).isTrue();
        }

        @Test
        @DisplayName("LLAMA_CPP_GRAMMAR → canRetry hints")
        void llamaCppGrammarHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("400 error parsing grammar"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.LLAMA_CPP_GRAMMAR);
            assertThat(result.hints().retryable()).isTrue();
        }

        @Test
        @DisplayName("MULTIMODAL_TOOL_CONTENT → canRetry hints")
        void multimodalToolContentHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("tool message content must be a string"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.MULTIMODAL_TOOL_CONTENT);
            assertThat(result.hints().retryable()).isTrue();
        }

        @Test
        @DisplayName("LONG_CONTEXT_TIER (tier gate) → compressAndRetry hints")
        void longContextTierHints() {
            ErrorClassifier.ClassificationResult result = classifier.classifyWithHints(
                new RuntimeException("429 extra usage tier for long context"));
            assertThat(result.type()).isEqualTo(ErrorClassifier.ErrorType.LONG_CONTEXT_TIER);
            assertThat(result.hints().retryable()).isTrue();
            assertThat(result.hints().shouldCompress()).isTrue();
        }
    }
}