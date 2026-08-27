package com.azhukov.agent.bot.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-429 classification (Hermes error_classifier priority): provider-side
 * errors (LiteLLM "No deployments available", ChatGPT weekly usage limit)
 * must NEVER render as "Rate limited by Telegram". Only genuine Telegram
 * API 429/flood errors do.
 */
class StreamErrorClassificationTest {

    private static final String LITELLM_NO_DEPLOYMENTS =
        "{\"error\":{\"message\":\"No deployments available for selected model, "
        + "Try again in 600 seconds. Passed model=app-test\",\"code\":\"429\"}}";

    private static final String CHATGPT_WEEKLY_LIMIT =
        "You've hit your weekly usage limit ChatGPTException: add extra usage to continue";

    private static final String TELEGRAM_FLOOD =
        "Too Many Requests: retry after 6";

    @Test
    void litellmNoDeploymentsIsProviderErrorNotTelegram() {
        String out = StreamingOrchestrator.toUserFriendlyError(
            new RuntimeException(LITELLM_NO_DEPLOYMENTS));
        assertThat(out)
            .doesNotContain("Telegram")
            .contains("Model provider");
    }

    @Test
    void providerOverloadMentionsCooldownNotTelegram() {
        String out = StreamingOrchestrator.toUserFriendlyError(
            new RuntimeException(LITELLM_NO_DEPLOYMENTS));
        assertThat(out).contains("overloaded");
    }

    @Test
    void chatgptWeeklyUsageLimitIsBillingNotTelegram() {
        String out = StreamingOrchestrator.toUserFriendlyError(
            new RuntimeException(CHATGPT_WEEKLY_LIMIT));
        assertThat(out)
            .doesNotContain("Telegram")
            .contains("usage limit");
    }

    @Test
    void genuineTelegram429StillSaysTelegram() {
        String out = StreamingOrchestrator.toUserFriendlyError(
            new RuntimeException(TELEGRAM_FLOOD));
        assertThat(out).contains("Rate limited by Telegram");
    }

    @Test
    void plainRateLimitWithoutProviderMarkersIsTelegram() {
        String out = StreamingOrchestrator.toUserFriendlyError(
            new RuntimeException("Rate limit exceeded"));
        assertThat(out).contains("Rate limited by Telegram");
    }
}
