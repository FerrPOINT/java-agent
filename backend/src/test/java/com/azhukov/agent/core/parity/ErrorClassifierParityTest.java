package com.azhukov.agent.core.parity;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARDS: classification behavior parity with the Hermes
 * reference (agent/error_classifier.py — 22 pattern lists, 198 patterns,
 * full set ported 2026-08-22). One representative message per category:
 * if a pattern set drifts (pattern dropped or category order changed),
 * the corresponding classification flips and this test fails.
 */
class ErrorClassifierParityTest {

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource({
        // message, expected ErrorType name (java enum)
        "'Error code: 429 - Rate limit exceeded', RATE_LIMIT",
        "'insufficient_quota: Please check your plan', BILLING",
        "'402 Payment Required: top up your credits', BILLING",
        "'maximum context length is 4096 tokens for this model', CONTEXT_OVERFLOW",
        "'request entity too large', PAYLOAD_TOO_LARGE",
        "'your request was flagged by the moderation system', CONTENT_POLICY",
        "'invalid_api_key: Incorrect API key provided', AUTH",
        "'The engine is currently overloaded', OVERLOADED",
        "'getaddrinfo failed', RETRYABLE",
        "'no endpoints found that support tool use', MODEL_NOT_FOUND",
    })
    @DisplayName("representative messages classify like Hermes")
    void classificationParity(String message, String expected) {
        ErrorClassifier classifier = new ErrorClassifier();
        ErrorClassifier.ErrorType t = classifier.classify(new RuntimeException(message));
        assertThat(t.name())
            .as("message: %s", message)
            .isEqualTo(expected);
    }
}
