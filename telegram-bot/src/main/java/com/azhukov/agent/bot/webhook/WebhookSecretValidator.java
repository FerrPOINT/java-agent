package com.azhukov.agent.bot.webhook;

import org.springframework.stereotype.Component;

@Component
public class WebhookSecretValidator {

    private final String expectedSecret;

    public WebhookSecretValidator(String expectedSecret) {
        this.expectedSecret = expectedSecret != null ? expectedSecret : "";
    }

    public boolean isValid(String secretHeader) {
        // Fail-closed: if secret is configured, it must match exactly
        if (expectedSecret.isBlank()) {
            // No secret configured — reject all (fail-closed)
            return false;
        }
        return expectedSecret.equals(secretHeader);
    }

    public boolean isConfigured() {
        return !expectedSecret.isBlank();
    }
}