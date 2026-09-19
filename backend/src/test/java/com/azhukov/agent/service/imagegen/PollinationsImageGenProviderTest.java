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
}
