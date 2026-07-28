package com.azhukov.agent.client.langchain4j;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeoutException;
import java.util.Locale;

/**
 * Classifies exceptions into categories that drive retry/backoff behaviour.
 */
@Component
@Slf4j
public class ErrorClassifier {

    public enum ErrorType {
        RETRYABLE,
        PERMANENT,
        RATE_LIMIT
    }

    /**
     * Classify the given exception into an {@link ErrorType}.
     *
     * @param exception the exception to classify
     * @return the classified error type
     */
    public ErrorType classify(Exception exception) {
        if (exception == null) {
            return ErrorType.RETRYABLE;
        }

        String message = exception.getMessage();
        String lowerMessage = message != null ? message.toLowerCase(Locale.ROOT) : "";

        // Rate limit
        if (lowerMessage.contains("rate limit") || lowerMessage.contains("429") || lowerMessage.contains("too many requests")) {
            return ErrorType.RATE_LIMIT;
        }

        // Timeout
        if (exception instanceof TimeoutException) {
            return ErrorType.RETRYABLE;
        }
        if (lowerMessage.contains("timeout") || lowerMessage.contains("timed out")) {
            return ErrorType.RETRYABLE;
        }

        // Permanent — invalid key / API
        if (exception instanceof IllegalArgumentException) {
            return ErrorType.PERMANENT;
        }
        if (lowerMessage.contains("invalid") && (lowerMessage.contains("key") || lowerMessage.contains("api"))) {
            return ErrorType.PERMANENT;
        }

        // Connection issues
        if (lowerMessage.contains("connection") || lowerMessage.contains("refused") || lowerMessage.contains("reset")) {
            return ErrorType.RETRYABLE;
        }

        // Default: safer to retry
        return ErrorType.RETRYABLE;
    }
}