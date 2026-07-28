package com.azhukov.agent.bot.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSecretValidatorTest {

    @Test
    void validSecret_returnsTrue() {
        WebhookSecretValidator validator = new WebhookSecretValidator("my-secret");
        assertThat(validator.isValid("my-secret")).isTrue();
    }

    @Test
    void invalidSecret_returnsFalse() {
        WebhookSecretValidator validator = new WebhookSecretValidator("my-secret");
        assertThat(validator.isValid("wrong")).isFalse();
    }

    @Test
    void nullSecret_returnsFalse() {
        WebhookSecretValidator validator = new WebhookSecretValidator("my-secret");
        assertThat(validator.isValid(null)).isFalse();
    }

    @Test
    void emptySecretNotConfigured_returnsFalse() {
        WebhookSecretValidator validator = new WebhookSecretValidator("");
        assertThat(validator.isValid("anything")).isFalse();
        assertThat(validator.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_trueWhenSecretSet() {
        WebhookSecretValidator validator = new WebhookSecretValidator("abc");
        assertThat(validator.isConfigured()).isTrue();
    }
}