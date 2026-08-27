package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P-06: OpenRouter response-cache bypass on empty-response retries. */
class EmptyRetryCacheBypassTest {

    @AfterEach
    void clear() {
        EmptyRetryCacheBypass.clear();
    }

    @Test
    void openRouterRetryCarriesBypassHeader() {
        EmptyRetryCacheBypass.markEmptyRetry();
        Map<String, String> headers = EmptyRetryCacheBypass.headersFor(
            "https://openrouter.ai/api/v1", Map.of());
        assertThat(headers).containsEntry(EmptyRetryCacheBypass.CACHE_HEADER, "false");
    }

    @Test
    void openRouterNonRetryHasNoBypassHeader() {
        Map<String, String> headers = EmptyRetryCacheBypass.headersFor(
            "https://openrouter.ai/api/v1", Map.of());
        assertThat(headers).doesNotContainKey(EmptyRetryCacheBypass.CACHE_HEADER);
    }

    @Test
    void nonOpenRouterRetryIsUntouched() {
        EmptyRetryCacheBypass.markEmptyRetry();
        Map<String, String> headers = EmptyRetryCacheBypass.headersFor(
            "http://192.168.10.1:4000/v1", Map.of());
        assertThat(headers).doesNotContainKey(EmptyRetryCacheBypass.CACHE_HEADER);
    }

    @Test
    void configuredHeadersArePreservedAndBypassOverrides() {
        EmptyRetryCacheBypass.markEmptyRetry();
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("X-Custom-Header", "preserved");
        Map<String, String> headers = EmptyRetryCacheBypass.headersFor(
            "https://openrouter.ai/api/v1", configured);
        assertThat(headers).containsEntry("X-Custom-Header", "preserved");
        assertThat(headers).containsEntry(EmptyRetryCacheBypass.CACHE_HEADER, "false");
    }

    @Test
    void configuredHeadersPassThroughWithoutRetry() {
        Map<String, String> configured = Map.of("X-Custom-Header", "preserved");
        Map<String, String> headers = EmptyRetryCacheBypass.headersFor(
            "https://openrouter.ai/api/v1", configured);
        assertThat(headers).containsExactlyEntriesOf(configured);
    }

    @Test
    void clearResetsFlag() {
        EmptyRetryCacheBypass.markEmptyRetry();
        EmptyRetryCacheBypass.clear();
        assertThat(EmptyRetryCacheBypass.isEmptyRetry()).isFalse();
    }

    @Test
    void openRouterDetectionIsCaseInsensitiveAndSubdomainSafe() {
        assertThat(EmptyRetryCacheBypass.isOpenRouterUrl("https://OpenRouter.ai/api/v1")).isTrue();
        assertThat(EmptyRetryCacheBypass.isOpenRouterUrl("https://api.openrouter.ai/v1")).isTrue();
        assertThat(EmptyRetryCacheBypass.isOpenRouterUrl("https://myopenrouter.example.com")).isFalse();
        assertThat(EmptyRetryCacheBypass.isOpenRouterUrl(null)).isFalse();
    }
}
