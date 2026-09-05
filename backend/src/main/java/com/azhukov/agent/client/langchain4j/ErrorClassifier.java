package com.azhukov.agent.client.langchain4j;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;
import java.util.Locale;

/**
 * Classifies exceptions into categories that drive retry/backoff behaviour.
 * <p>
 * Parity with Hermes {@code error_classifier.py} FailoverReason enum (20+ values).
 */
@Component
@Slf4j
public class ErrorClassifier {

    /** M33: token-bound HTTP status patterns — reject digits embedded in longer numbers. */
    private static final java.util.regex.Pattern HTTP_400 =
        java.util.regex.Pattern.compile("(?<![0-9.])400(?![0-9])");
    private static final java.util.regex.Pattern HTTP_429 =
        java.util.regex.Pattern.compile("(?<![0-9.])429(?![0-9])");

    public enum ErrorType {
        RETRYABLE,
        PERMANENT,
        RATE_LIMIT,
        BILLING,
        CONTEXT_OVERFLOW,
        CONTENT_POLICY,
        // Extended categories — parity with Hermes FailoverReason
        AUTH,              // 401 — token expired or invalid, may need rotation
        AUTH_PERMANENT,    // 403 — key revoked, no rotation will help
        OVERLOADED,        // 529 — provider overloaded (Anthropic-specific)
        SERVER_ERROR,      // 500 — internal server error, retryable
        TIMEOUT,           // Read/connect timeout
        MODEL_NOT_FOUND,   // 404 — model name wrong or not available
        FORMAT_ERROR,      // 422 — request format rejected by API
        // Additional categories — parity with Hermes FailoverReason (20+ values)
        PAYLOAD_TOO_LARGE,             // 413 — request entity too large
        IMAGE_TOO_LARGE,               // 413/400 with image context — image exceeds provider limit
        PROVIDER_POLICY_BLOCKED,        // 403/404 — aggregator policy block (e.g. OpenRouter privacy)
        INVALID_ENCRYPTED_CONTENT,      // 400 with encrypted_content — Responses replay blob rejected
        THINKING_SIGNATURE,            // 400 with thinking/sig — Anthropic thinking block signature invalid
        LONG_CONTEXT_TIER,             // 429 with "extra usage" or "long context" — tier gate
        LLAMA_CPP_GRAMMAR,             // 400 with grammar pattern — llama.cpp json-schema-to-grammar rejection
        // MULTIMODAL_TOOL_CONTENT — stripped from FORMAT_ERROR; models that reject list-type tool content
        MULTIMODAL_TOOL_CONTENT,        // 400 — provider rejected list-type content in tool messages
        // h81: Empty-response advisory — model returned a deterministic empty response.
        // This is advisory only — it should NOT trigger compression or retry.
        EMPTY_RESPONSE,
        // SSL certificate verification failure — non-retryable (Hermes parity:
        // FailoverReason.ssl_cert_verification). Broken cert chain is deterministic
        // for the host; retrying burns budget without chance of success.
        SSL_CERT_VERIFICATION
    }

    /**
     * Recovery hints — tells the caller what recovery action is appropriate.
     * Mirrors Hermes ClassifiedError recovery hints.
     */
    public record RecoveryHints(
        boolean retryable,
        boolean shouldCompress,
        boolean shouldRotateCredential,
        boolean shouldFallback
    ) {
        public static RecoveryHints canRetry() {
            return new RecoveryHints(true, false, false, false);
        }
        public static RecoveryHints compressAndRetry() {
            return new RecoveryHints(true, true, false, false);
        }
        public static RecoveryHints rotateAndRetry() {
            return new RecoveryHints(true, false, true, false);
        }
        public static RecoveryHints noRetry() {
            return new RecoveryHints(false, false, false, false);
        }
        public static RecoveryHints switchModel() {
            return new RecoveryHints(false, false, false, true);
        }
        public static RecoveryHints compressAndRetryWithFallback() {
            return new RecoveryHints(true, true, false, true);
        }
        public static RecoveryHints rotateAndFallback() {
            return new RecoveryHints(false, false, true, true);
        }
        public static RecoveryHints fallback() {
            return new RecoveryHints(false, false, false, true);
        }
        // h81: Advisory-only — no retry, no compression, no fallback, no credential rotation.
        public static RecoveryHints advisory() {
            return new RecoveryHints(false, false, false, false);
        }
    }

    /**
     * Classify the given exception into an {@link ErrorType}.
     *
     * @param exception the exception to classify
     * @return the classified error type
     */
    public ErrorType classify(Exception exception) {
        return classifyWithHints(exception).type();
    }

    /**
     * Classify the exception and return recovery hints.
     * Mirrors Hermes ClassifiedError with recovery action hints.
     * <p>
     * Priority-ordered pipeline (matching Hermes {@code classify_api_error}):
     * 1. Content-policy blocks (deterministic per-request, don't retry)
     * 2. Thinking signature (400 + thinking/sig)
     * 3. Long context tier (429 + "extra usage" + "long context")
     * 4. OAuth 1M beta forbidden (400 + "long context beta" + "not yet available")
     * 5. llama.cpp grammar (400 + grammar patterns)
     * 6. Invalid encrypted content (400 + encrypted_content)
     * 7. Billing (402 / billing patterns)
     * 8. Context overflow
     * 9. Payload too large (413)
     * 10. Image too large (413/400 + image patterns)
     * 11. Provider policy blocked (403/404 + policy patterns)
     * 12. Auth (401)
     * 13. Auth permanent (403)
     * 14. Model not found (404)
     * 15. Format error (422)
     * 16. Overloaded (529)
     * 17. Server error (500/503)
     * 18. Rate limit (429)
     * 19. Timeout
     * 20. Permanent (invalid key/api)
     * 21. Connection issues
     * 22. Default: RETRYABLE
     */
    public ClassificationResult classifyWithHints(Exception exception) {
        if (exception == null) {
            return new ClassificationResult(ErrorType.RETRYABLE, RecoveryHints.canRetry());
        }

        String message = exception.getMessage();
        String lowerMessage = message != null ? message.toLowerCase(Locale.ROOT) : "";
        // M33 fix: HTTP status codes must be matched as standalone tokens, not
        // substrings — "400" inside "14007 tokens" or a request id must not
        // classify as a 400 error. Negative lookaround keeps "400", "400.0",
        // "(400)", "status=400" matches while rejecting embedded digits.
        boolean has400 = HTTP_400.matcher(lowerMessage).find();
        boolean has429 = HTTP_429.matcher(lowerMessage).find();

        // ── 1. Content-policy blocks (highest priority — deterministic, don't retry) ──
        // Mirrors Hermes _CONTENT_POLICY_BLOCKED_PATTERNS
        if (lowerMessage.contains("content policy") || lowerMessage.contains("content filter")
            || lowerMessage.contains("content management") || lowerMessage.contains("safety")
            || lowerMessage.contains("harmful content") || lowerMessage.contains("prohibited content")
            // OpenAI Codex cybersecurity refusal
            || lowerMessage.contains("flagged for possible cybersecurity risk")
            || lowerMessage.contains("trusted access for cyber")
            // OpenAI moderation
            || lowerMessage.contains("violates our usage policies")
            || lowerMessage.contains("violates openai's usage policies")
            || lowerMessage.contains("your request was flagged by")
            || lowerMessage.contains("new_sensitive")
            // Anthropic safety system
            || lowerMessage.contains("prompt was flagged by our safety")
            || lowerMessage.contains("responses cannot be generated due to safety")
            // Azure / OpenAI Responses content filter
            || lowerMessage.contains("content_filter")
            || lowerMessage.contains("responsibleaipolicyviolation")) {
            return new ClassificationResult(ErrorType.CONTENT_POLICY, RecoveryHints.fallback());
        }

        // ── 2. Thinking signature (400 + thinking/sig) ──
        // Mirrors Hermes FailoverReason.thinking_signature
        if (has400
            && lowerMessage.contains("thinking")
            && (lowerMessage.contains("signature")
                || lowerMessage.contains("cannot be modified")
                || lowerMessage.contains("must remain as they were"))) {
            return new ClassificationResult(ErrorType.THINKING_SIGNATURE, RecoveryHints.canRetry());
        }

        // ── 3. Long context tier (429 + "extra usage" + "long context") ──
        // Mirrors Hermes FailoverReason.long_context_tier
        if (has429
            && lowerMessage.contains("extra usage")
            && lowerMessage.contains("long context")) {
            return new ClassificationResult(ErrorType.LONG_CONTEXT_TIER, RecoveryHints.compressAndRetry());
        }

        // ── 4. OAuth 1M beta forbidden (400 + "long context beta" + "not yet available") ──
        // Mirrors Hermes FailoverReason.oauth_long_context_beta_forbidden
        if (has400
            && lowerMessage.contains("long context beta")
            && lowerMessage.contains("not yet available")) {
            return new ClassificationResult(ErrorType.LONG_CONTEXT_TIER, RecoveryHints.canRetry());
        }

        // ── 5. llama.cpp grammar pattern (400 + grammar patterns) ──
        // Mirrors Hermes FailoverReason.llama_cpp_grammar_pattern
        if (has400
            && (lowerMessage.contains("error parsing grammar")
                || lowerMessage.contains("json-schema-to-grammar")
                || (lowerMessage.contains("unable to generate parser") && lowerMessage.contains("template")))) {
            return new ClassificationResult(ErrorType.LLAMA_CPP_GRAMMAR, RecoveryHints.canRetry());
        }

        // ── 6. Invalid encrypted content (400 + encrypted_content) ──
        // Mirrors Hermes FailoverReason.invalid_encrypted_content
        if (has400
            && (lowerMessage.contains("encrypted_content")
                || lowerMessage.contains("encrypted content")
                || lowerMessage.contains("invalid encrypted"))) {
            return new ClassificationResult(ErrorType.INVALID_ENCRYPTED_CONTENT, RecoveryHints.canRetry());
        }

        // ── 7a. Usage-limit disambiguation (Hermes _USAGE_LIMIT_PATTERNS) ──
        // "usage limit" / "quota" can be either a transient periodic quota
        // (rate_limit → retry with backoff) or genuine billing exhaustion
        // (billing → fail fast, no retry). Hermes _classify_by_message:
        // a transient signal ("try again", "resets at", "retry", …) means
        // periodic quota; otherwise it is billing. Ollama Cloud sends
        // "you have reached your weekly usage limit, add extra usage: …"
        // with NO transient signal → billing (non-retryable), previously
        // misclassified as RETRYABLE and burned 4 paid retries per call.
        //
        // Hermes reaches this branch only for STATUS-LESS errors (its
        // status-bearing path handles 429/GLM-400 earlier). This classifier
        // is message-only, so guard the two known status-bearing collisions:
        // "rate limit exceeded" (429) and "token limit exceeded" (GLM 400)
        // both contain the bare "limit exceeded" substring but must keep
        // their rate-limit / context-overflow semantics.
        boolean rateLimitOrTokenLimitContext = lowerMessage.contains("rate limit")
            || lowerMessage.contains("token limit") || lowerMessage.contains("rate_limit");
        boolean hasUsageLimit = !rateLimitOrTokenLimitContext
            && (lowerMessage.contains("usage limit") || lowerMessage.contains("quota")
                || lowerMessage.contains("limit exceeded") || lowerMessage.contains("key limit exceeded"));
        if (hasUsageLimit) {
            boolean hasTransientSignal = lowerMessage.contains("try again") || lowerMessage.contains("retry")
                || lowerMessage.contains("resets at") || lowerMessage.contains("reset in")
                || lowerMessage.contains("wait") || lowerMessage.contains("requests remaining")
                || lowerMessage.contains("periodic") || lowerMessage.contains("window");
            if (hasTransientSignal) {
                // Transient periodic quota — rate limit semantics
                return new ClassificationResult(ErrorType.RATE_LIMIT, RecoveryHints.rotateAndRetry());
            }
            return new ClassificationResult(ErrorType.BILLING, RecoveryHints.rotateAndFallback());
        }

        // ── 7. Billing (402 / billing patterns) ──
        // Mirrors Hermes _BILLING_PATTERNS (21 strings, full parity 2026-08-22)
        if (lowerMessage.contains("402")
            || lowerMessage.contains("insufficient credits") || lowerMessage.contains("insufficient_quota")
            || lowerMessage.contains("insufficient balance") || lowerMessage.contains("credit balance")
            || lowerMessage.contains("credits exhausted") || lowerMessage.contains("credits have been exhausted")
            || lowerMessage.contains("requires available credits")
            || lowerMessage.contains("account balance is too low")
            || lowerMessage.contains("no usable credits") || lowerMessage.contains("top up your credits")
            || lowerMessage.contains("billing quota") || lowerMessage.contains("payment required")
            || lowerMessage.contains("billing hard limit") || lowerMessage.contains("quota exceeded")
            || lowerMessage.contains("exceeded your current quota")
            || lowerMessage.contains("account is deactivated")
            || lowerMessage.contains("plan does not include")
            || lowerMessage.contains("out of extra usage")
            || lowerMessage.contains("out of funds") || lowerMessage.contains("run out of funds")
            || lowerMessage.contains("balance_depleted")
            || lowerMessage.contains("model_not_supported_on_free_tier")
            || lowerMessage.contains("not available on the free tier")
            || lowerMessage.contains("billing")) {
            return new ClassificationResult(ErrorType.BILLING, RecoveryHints.rotateAndFallback());
        }

        // ── 8. Context overflow — compress and retry ──
        // h82: Also match Z.AI GLM token-limit messages as context overflow.
        if (lowerMessage.contains("context length") || lowerMessage.contains("context window")
            || lowerMessage.contains("maximum context") || lowerMessage.contains("token limit exceeded")
            || lowerMessage.contains("context_length_exceeded")
            || lowerMessage.contains("context size") || lowerMessage.contains("too many tokens")
            || lowerMessage.contains("reduce the length") || lowerMessage.contains("exceeds the limit")
            || lowerMessage.contains("prompt is too long") || lowerMessage.contains("prompt exceeds max length")
            || lowerMessage.contains("max_model_len") || lowerMessage.contains("prompt length")
            || lowerMessage.contains("input is too long") || lowerMessage.contains("maximum model length")
            || lowerMessage.contains("context length exceeded")
            // h82: Z.AI GLM token-limit messages
            || lowerMessage.contains("token limit reached")
            || lowerMessage.contains("maximum context length")
            || lowerMessage.contains("input too long")
            // Hermes parity (error_classifier.py _CONTEXT_OVERFLOW_PATTERNS): Z.AI/Zhipu
            // GLM error-code-1210 English form, Chinese provider messages, Ollama slot
            // context, and Together/Fireworks input-length wording.
            || lowerMessage.contains("tokens in request more than max tokens allowed")
            || lowerMessage.contains("超过最大长度")
            || lowerMessage.contains("上下文长度")
            || lowerMessage.contains("slot context")
            || lowerMessage.contains("n_ctx_slot")
            || lowerMessage.contains("maximum allowed input length")
            || lowerMessage.contains("max input token")
            // Hermes parity (error_classifier.py _CONTEXT_OVERFLOW_PATTERNS, full set 2026-08-22):
            || lowerMessage.contains("max_tokens")                     // bare max_tokens hint (Ollama/LiteLLM)
            || lowerMessage.contains("maximum number of tokens")
            || lowerMessage.contains("exceeds the max_model_len")
            || lowerMessage.contains("engine prompt length")
            || lowerMessage.contains("truncating input")               // vLLM truncation notice
            || lowerMessage.contains("slot context")                   // llama.cpp slot hint
            || lowerMessage.contains("exceeds the maximum number of input tokens")
            || lowerMessage.contains("input token")) {                 // Bedrock "input token" family
            return new ClassificationResult(ErrorType.CONTEXT_OVERFLOW, RecoveryHints.compressAndRetry());
        }

        // ── 9. Payload too large (413) ──
        // Mirrors Hermes FailoverReason.payload_too_large + _PAYLOAD_TOO_LARGE_PATTERNS (full set)
        if (lowerMessage.contains("413")
            || lowerMessage.contains("request entity too large")
            || lowerMessage.contains("payload too large")
            || lowerMessage.contains("error code: 413")
            || lowerMessage.contains("request_too_large")
            || lowerMessage.contains("request exceeds the maximum size")) {
            return new ClassificationResult(ErrorType.PAYLOAD_TOO_LARGE, RecoveryHints.compressAndRetry());
        }

        // ── 10. Image too large (413/400 + image patterns) ──
        // Mirrors Hermes FailoverReason.image_too_large + _IMAGE_TOO_LARGE_PATTERNS (full set)
        if (lowerMessage.contains("image exceeds")
            || lowerMessage.contains("image exceeds 5 mb maximum")
            || lowerMessage.contains("image too large")
            || lowerMessage.contains("image_too_large")
            || lowerMessage.contains("image size exceeds")
            || lowerMessage.contains("image dimensions exceed")
            || lowerMessage.contains("image dimensions exceed max allowed size: 8000 pixels")
            || lowerMessage.contains("dimensions exceed max allowed size")
            || lowerMessage.contains("max allowed size: 8000")) {
            return new ClassificationResult(ErrorType.IMAGE_TOO_LARGE, RecoveryHints.compressAndRetry());
        }

        // ── 11. Provider policy blocked (403/404 + policy patterns) ──
        // Mirrors Hermes FailoverReason.provider_policy_blocked + _PROVIDER_POLICY_BLOCKED_PATTERNS
        if (lowerMessage.contains("no endpoints available matching your guardrail")
            || lowerMessage.contains("no endpoints available matching your data policy")
            || lowerMessage.contains("no endpoints found matching your data policy")) {
            return new ClassificationResult(ErrorType.PROVIDER_POLICY_BLOCKED, RecoveryHints.noRetry());
        }

        // ── 11b. Invalid message body (Hermes _INVALID_MESSAGE_BODY_PATTERNS) ──
        // Non-retryable with the same payload; classify as FORMAT_ERROR family.
        if (lowerMessage.contains("must have non-empty content")
            || lowerMessage.contains("messages must have non-empty")
            || lowerMessage.contains("invalid_request_body")
            || lowerMessage.contains("text content blocks must be non-empty")
            || lowerMessage.contains("content field is required")
            || lowerMessage.contains("messages: at least one message is required")) {
            return new ClassificationResult(ErrorType.FORMAT_ERROR, RecoveryHints.noRetry());
        }

        // ── 11a. Malformed request history — non-retryable with same history ──
        // litellm wraps upstream request-shape rejections (Gemini "Missing
        // corresponding tool call for tool response message", DeepSeek "an
        // assistant message with 'tool_calls' must be followed by...") into
        // APIConnectionError/ChatgptException envelopes. Retrying the SAME
        // broken history is guaranteed to fail — and the wrapper text
        // ("ChatgptException", "connection error") otherwise matches AUTH /
        // NETWORK patterns, causing pointless retry loops. Classify as
        // CONTEXT_OVERFLOW-family so the agent compresses/rebuilds history
        // instead of hammering the provider.
        if (lowerMessage.contains("missing corresponding tool call for tool response message")
            || lowerMessage.contains("must be followed by tool messages")
            || lowerMessage.contains("tool_calls' must be followed by")
            || lowerMessage.contains("function call turn comes immediately after")
            || lowerMessage.contains("invalid 'messages[")
            || lowerMessage.contains("duplicate tool_call_id")) {
            return new ClassificationResult(ErrorType.CONTEXT_OVERFLOW, RecoveryHints.compressAndRetry());
        }

        // ── 12. Auth errors — 401 ──
        // Hermes _AUTH_PATTERNS full set (2026-08-22)
        if (lowerMessage.contains("401") || lowerMessage.contains("unauthorized")
            || lowerMessage.contains("invalid api key") || lowerMessage.contains("invalid_api_key")
            || lowerMessage.contains("gateway_auth_failed")
            || lowerMessage.contains("authentication failed")
            || lowerMessage.contains("authentication")
            || lowerMessage.contains("invalid token") || lowerMessage.contains("token expired")
            || lowerMessage.contains("token revoked")) {
            return new ClassificationResult(ErrorType.AUTH, RecoveryHints.rotateAndRetry());
        }

        // ── 13. Auth permanent — 403 ──
        // Check billing-403 first (key limit exceeded, spending limit)
        if (lowerMessage.contains("403") || lowerMessage.contains("forbidden")
            || lowerMessage.contains("access denied") || lowerMessage.contains("permission denied")) {
            // OpenRouter 403 "key limit exceeded" is actually billing
            if (lowerMessage.contains("key limit exceeded") || lowerMessage.contains("spending limit")) {
                return new ClassificationResult(ErrorType.BILLING, RecoveryHints.rotateAndFallback());
            }
            return new ClassificationResult(ErrorType.AUTH_PERMANENT, RecoveryHints.fallback());
        }

        // ── 14. Model not found — 404 ──
        if (lowerMessage.contains("404") || lowerMessage.contains("model not found")
            || lowerMessage.contains("model_not_found") || lowerMessage.contains("does not exist")
            || lowerMessage.contains("no such model") || lowerMessage.contains("is not a valid model")
            || lowerMessage.contains("invalid model") || lowerMessage.contains("unknown model")
            || lowerMessage.contains("unsupported model")
            // Hermes parity 2026-08-22: tool-use gating surfaces as model-unavailable
            || lowerMessage.contains("no endpoints found that support tool use")) {
            return new ClassificationResult(ErrorType.MODEL_NOT_FOUND, RecoveryHints.switchModel());
        }

        // ── 15. Format error — 422 ──
        if (lowerMessage.contains("422") || lowerMessage.contains("unprocessable")
            || (lowerMessage.contains("invalid_request_error") && lowerMessage.contains("format"))
            || lowerMessage.contains("unknown parameter") || lowerMessage.contains("unsupported parameter")
            || lowerMessage.contains("unrecognized request argument")
            || lowerMessage.contains("unknown_parameter") || lowerMessage.contains("unsupported_parameter")) {
            return new ClassificationResult(ErrorType.FORMAT_ERROR, RecoveryHints.noRetry());
        }

        // ── Multimodal tool content — provider rejected list-type content in tool messages ──
        // Mirrors Hermes _MULTIMODAL_TOOL_CONTENT_PATTERNS
        if (lowerMessage.contains("text is not set")
            || lowerMessage.contains("tool message content must be a string")
            || lowerMessage.contains("tool content must be a string")
            || lowerMessage.contains("tool message must be a string")
            || lowerMessage.contains("expected string, got list")
            || lowerMessage.contains("expected string, got array")
            || lowerMessage.contains("tool_call.content must be string")) {
            return new ClassificationResult(ErrorType.MULTIMODAL_TOOL_CONTENT, RecoveryHints.canRetry());
        }

        // ── 16. Provider overloaded (Anthropic-specific 529 + Hermes _OVERLOADED_PATTERNS) ──
        if (lowerMessage.contains("529") || lowerMessage.contains("overloaded")
            || lowerMessage.contains("model is overloaded")
            || lowerMessage.contains("temporarily overloaded")
            || lowerMessage.contains("service is temporarily overloaded")
            || lowerMessage.contains("service may be temporarily overloaded")
            || lowerMessage.contains("server is overloaded")
            || lowerMessage.contains("server overloaded")
            || lowerMessage.contains("service overloaded")
            || lowerMessage.contains("service is overloaded")
            || lowerMessage.contains("upstream overloaded")
            || lowerMessage.contains("currently overloaded")
            || lowerMessage.contains("at capacity")
            || lowerMessage.contains("over capacity")) {
            return new ClassificationResult(ErrorType.OVERLOADED, RecoveryHints.canRetry());
        }

        // ── 17. Server error — 500/503 ──
        if (lowerMessage.contains("500") || lowerMessage.contains("internal server error")
            || lowerMessage.contains("server error") || lowerMessage.contains("503")
            || lowerMessage.contains("service unavailable") || lowerMessage.contains("temporarily unavailable")
            || lowerMessage.contains("please try again later")) {
            return new ClassificationResult(ErrorType.SERVER_ERROR, RecoveryHints.canRetry());
        }

        // ── 18. Rate limit — 429 ──
        // Hermes _RATE_LIMIT_PATTERNS full set (2026-08-22)
        if (lowerMessage.contains("rate limit") || has429
            || lowerMessage.contains("rate_limit")
            || lowerMessage.contains("too many requests")
            || lowerMessage.contains("throttled") || lowerMessage.contains("resource_exhausted")
            || lowerMessage.contains("requests per minute") || lowerMessage.contains("tokens per minute")
            || lowerMessage.contains("requests per day")
            || lowerMessage.contains("try again in") || lowerMessage.contains("please retry after")
            || lowerMessage.contains("rate increased too quickly")
            || lowerMessage.contains("throttlingexception")
            || lowerMessage.contains("too many concurrent requests")
            || lowerMessage.contains("servicequotaexceededexception")
            || lowerMessage.contains("throttling")) {
            return new ClassificationResult(ErrorType.RATE_LIMIT, RecoveryHints.canRetry());
        }

        // ── 18b. Empty provider response (Hermes _EMPTY_PROVIDER_RESPONSE_PATTERNS) ──
        // Advisory, not an error to retry against the same payload shape.
        if (lowerMessage.contains("returned an empty response")
            || lowerMessage.contains("empty response despite retries")
            || lowerMessage.contains("provider returned an empty response")
            || lowerMessage.contains("model returning empty responses")
            || lowerMessage.contains("empty response stream")) {
            return new ClassificationResult(ErrorType.EMPTY_RESPONSE, RecoveryHints.advisory());
        }

        // ── 19. Timeout (Hermes _TIMEOUT_MESSAGE_PATTERNS full set) ──
        if (exception instanceof TimeoutException
            || lowerMessage.contains("timeout") || lowerMessage.contains("timed out")
            || lowerMessage.contains("turn timed out")
            || lowerMessage.contains("request timed out")
            || lowerMessage.contains("deadline exceeded") || lowerMessage.contains("operation timed out")
            || lowerMessage.contains("upstream timed out")) {
            return new ClassificationResult(ErrorType.TIMEOUT, RecoveryHints.canRetry());
        }

        // ── 20. Permanent — invalid key / API ──
        if (exception instanceof IllegalArgumentException) {
            return new ClassificationResult(ErrorType.PERMANENT, RecoveryHints.noRetry());
        }
        if (lowerMessage.contains("invalid") && (lowerMessage.contains("key") || lowerMessage.contains("api"))) {
            return new ClassificationResult(ErrorType.PERMANENT, RecoveryHints.noRetry());
        }

        // ── 20b. SSL certificate verification failures → fail fast ──────
        // Hermes parity (error_classifier.py:1100-1114): a broken certificate
        // chain (TLS-inspecting proxy, missing custom CA, expired/self-signed
        // cert) is deterministic for the host — every retry reproduces the
        // identical handshake failure. Fail immediately instead of burning
        // the retry budget. Checked BEFORE the transient-SSL/connection block
        // because cert-verify messages also contain "ssl:" / "certificate"
        // which would otherwise match the transient list.
        if (lowerMessage.contains("certificate verify failed") || lowerMessage.contains("certificate_verify_failed")
            || lowerMessage.contains("unable to get local issuer certificate")
            || lowerMessage.contains("self-signed certificate") || lowerMessage.contains("self signed certificate")
            || lowerMessage.contains("certificate has expired")
            || lowerMessage.contains("hostname mismatch, certificate is not valid")
            || lowerMessage.contains("unable to verify the first certificate")) {
            return new ClassificationResult(ErrorType.SSL_CERT_VERIFICATION, RecoveryHints.noRetry());
        }

        // ── 20b. Generic 400 Bad Request — deterministic request-shape rejection ──
        // Hermes _classify_400 tail: a 400 that matched no specific pattern is a
        // non-retryable format_error (fail fast + fall back). Retrying a rejected
        // request shape is guaranteed to fail identically (observed live: ZAI
        // "The messages parameter is illegal" burned attempts in a retry loop).
        if (lowerMessage.contains("badrequest")
            || has400
            || lowerMessage.contains("messages parameter is illegal")
            || lowerMessage.contains("bad request")) {
            return new ClassificationResult(ErrorType.FORMAT_ERROR, RecoveryHints.noRetry());
        }

        // ── 21. Connection issues ──
        // h80: Match on message content for connect/DNS failures on generic exception types,
        // not just specific exception classes.
        // Hermes parity (full sets 2026-08-22): _CONNECTION_MESSAGE_PATTERNS (DNS/connect),
        // _SERVER_DISCONNECT_PATTERNS (mid-stream disconnects),
        // _SSL_CERT_VERIFY_PATTERNS + _SSL_TRANSIENT_PATTERNS (TLS).
        if (lowerMessage.contains("connection") || lowerMessage.contains("refused") || lowerMessage.contains("reset")
            || lowerMessage.contains("connection refused")
            || lowerMessage.contains("econnrefused")
            || lowerMessage.contains("unknown host")
            || lowerMessage.contains("network is unreachable") || lowerMessage.contains("network unreachable")
            || lowerMessage.contains("no route to host")
            || lowerMessage.contains("name or service not known")
            || lowerMessage.contains("temporary failure in name resolution")
            || lowerMessage.contains("nodename nor servname provided")
            || lowerMessage.contains("getaddrinfo failed") || lowerMessage.contains("getaddrinfo enotfound")
            || lowerMessage.contains("eai_again")
            || lowerMessage.contains("fetch failed") || lowerMessage.contains("failed to fetch")
            || lowerMessage.contains("upstream connect error")
            // Server disconnects mid-stream
            || lowerMessage.contains("server disconnected")
            || lowerMessage.contains("peer closed connection")
            || lowerMessage.contains("connection reset by peer")
            || lowerMessage.contains("connection was closed")
            || lowerMessage.contains("network connection lost")
            || lowerMessage.contains("unexpected eof")
            || lowerMessage.contains("incomplete chunked read")
            // thinking_timeout_guidance.py parity: OSS-level transport kill
            || lowerMessage.contains("broken pipe")
            || lowerMessage.contains("errno 32")
            || lowerMessage.contains("remote protocol")
            // SSL cert verify patterns are handled above (20b) as non-retryable.
            // Only transient TLS/SSL alerts remain here:
            || lowerMessage.contains("bad record mac") || lowerMessage.contains("bad_record_mac")
            || lowerMessage.contains("ssl alert") || lowerMessage.contains("ssl_alert")
            || lowerMessage.contains("tls alert") || lowerMessage.contains("tls_alert")
            || lowerMessage.contains("tls_alert_internal_error")
            || lowerMessage.contains("ssl handshake failure")
            || lowerMessage.contains("tlsv1 alert") || lowerMessage.contains("sslv3 alert")) {
            return new ClassificationResult(ErrorType.RETRYABLE, RecoveryHints.canRetry());
        }

        // ── h81: Empty-response advisory ──
        // If the exception message indicates a deterministic empty response (not an error),
        // classify as EMPTY_RESPONSE with advisory hints — no compression, no retry.
        if (lowerMessage.contains("empty response") || lowerMessage.contains("empty content")
            || lowerMessage.contains("no content returned") || lowerMessage.contains("response was empty")) {
            return new ClassificationResult(ErrorType.EMPTY_RESPONSE, RecoveryHints.advisory());
        }

        // ── 22. Default: safer to retry ──
        return new ClassificationResult(ErrorType.RETRYABLE, RecoveryHints.canRetry());
    }

    /** Full classification result with type and recovery hints. */
    public record ClassificationResult(ErrorType type, RecoveryHints hints) {}
}