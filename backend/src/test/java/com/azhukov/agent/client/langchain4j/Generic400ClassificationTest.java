package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes _classify_400 tail parity: a generic 400 Bad Request that matches no
 * specific pattern is a NON-RETRYABLE format_error (fail fast + fall back).
 * Observed live: ZAI "The messages parameter is illegal" (400) was classified
 * RETRYABLE and burned the whole retry budget on a deterministic rejection.
 */
class Generic400ClassificationTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    void zaiMessagesParameterIllegalIsNonRetryable() {
        Exception e = new RuntimeException("400 Bad Request on POST /v1/chat/completions: "
            + "{\"error\":{\"message\":\"litellm.BadRequestError: ZaiException - "
            + "The messages parameter is illegal. Please check the documentation.\",\"code\":\"400\"}}");
        assertThat(classifier.classify(e)).isEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
    }

    @Test
    void plainBadRequestIsNonRetryable() {
        Exception e = new RuntimeException("dev.langchain4j.exception.BadRequestException: "
            + "{\"error\":{\"message\":\"bad_request: invalid payload\",\"type\":\"invalid_request_error\"}}");
        assertThat(classifier.classify(e)).isEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
    }

    @Test
    void rateLimitStillWinsOverGeneric400() {
        // 429 bodies may contain "400" inside cooldown text — rate-limit must win
        Exception e = new RuntimeException("429: {\"error\":{\"message\":\"No deployments available "
            + "for selected model, Try again in 600 seconds. Passed model=app-test\",\"code\":\"429\"}}");
        assertThat(classifier.classify(e)).isEqualTo(ErrorClassifier.ErrorType.RATE_LIMIT);
    }

    @Test
    void billingStillWinsOverGeneric400() {
        Exception e = new RuntimeException("400: you have reached your weekly usage limit, "
            + "add extra usage: https://ollama.com/settings");
        assertThat(classifier.classify(e)).isEqualTo(ErrorClassifier.ErrorType.BILLING);
    }

    @Test
    void serverError500StillRetryable() {
        Exception e = new RuntimeException("500 Internal Server Error: upstream connect error");
        assertThat(classifier.classify(e)).isNotEqualTo(ErrorClassifier.ErrorType.FORMAT_ERROR);
    }
}
