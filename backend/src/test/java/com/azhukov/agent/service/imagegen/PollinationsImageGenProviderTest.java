package com.azhukov.agent.service.imagegen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PollinationsImageGenProviderTest {

    @Test
    void retryableStatusesAreRateLimitAndServerError() {
        assertThat(PollinationsImageGenProvider.isRetryable(429)).isTrue();
        assertThat(PollinationsImageGenProvider.isRetryable(500)).isTrue();
        assertThat(PollinationsImageGenProvider.isRetryable(503)).isTrue();
        assertThat(PollinationsImageGenProvider.isRetryable(200)).isFalse();
        assertThat(PollinationsImageGenProvider.isRetryable(400)).isFalse();
        assertThat(PollinationsImageGenProvider.isRetryable(401)).isFalse();
        assertThat(PollinationsImageGenProvider.isRetryable(404)).isFalse();
    }

    @Test
    void aspectRatioMappingMatchesToolContract() {
        assertThat(PollinationsImageGenProvider.mapAspectRatio(null)).containsExactly(1024, 576);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("")).containsExactly(1024, 576);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("landscape")).containsExactly(1024, 576);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("9:16")).containsExactly(576, 1024);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("portrait")).containsExactly(576, 1024);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("1:1")).containsExactly(1024, 1024);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("square")).containsExactly(1024, 1024);
        assertThat(PollinationsImageGenProvider.mapAspectRatio("4:3")).containsExactly(1024, 576);
    }

    @Test
    void paymentFailureMessageIsExtractedFromWrapped500Body() {
        // Live-observed (session 8206abc2): Pollinations wraps a 402 payment failure
        // as HTTP 500 with the real cause in the JSON body. The provider must surface
        // that message instead of a generic "HTTP 500 after 3 attempts".
        String body = "{\"error\":{\"message\":\"Insufficient balance. This request costs ~0.0001 pollen, but your available balance is 0.0000. Top up at https://enter.pollinations.ai\",\"code\":402}}";
        assertThat(PollinationsImageGenProvider.extractQuotedMessage(body))
            .contains("Insufficient balance")
            .contains("0.0000");
    }

    @Test
    void extractQuotedMessageHandlesPlainAndEmptyBodies() {
        assertThat(PollinationsImageGenProvider.extractQuotedMessage(null)).isEmpty();
        assertThat(PollinationsImageGenProvider.extractQuotedMessage("")).isEmpty();
        assertThat(PollinationsImageGenProvider.extractQuotedMessage("<html>500</html>")).isEmpty();
        assertThat(PollinationsImageGenProvider.extractQuotedMessage("{\"message\":\"queued\"}")).isEqualTo("queued");
    }
}
