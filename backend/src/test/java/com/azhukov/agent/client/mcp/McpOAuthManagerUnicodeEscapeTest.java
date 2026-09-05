package com.azhukov.agent.client.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** M36 regression: JSON unicode escapes in OAuth response fields decode correctly. */
class McpOAuthManagerUnicodeEscapeTest {

    @Test
    void decodesUnicodeEscape() {
        String json = "{\"error_description\":\"\\u041d\\u0435\\u0430\\u0432\\u0442\\u043e\\u0440\\u0438\\u0437\\u043e\\u0432\\u0430\\u043d\"}";
        String value = McpOAuthManager.extractJsonField(json, "error_description");
        assertThat(value).isEqualTo("Неавторизован");
    }

    @Test
    void keepsLegacyEscapesWorking() {
        String json = "{\"error_description\":\"quote:\\\" newline done\"}";
        String value = McpOAuthManager.extractJsonField(json, "error_description");
        assertThat(value).isEqualTo("quote:\" newline done");
    }

    @Test
    void invalidUnicodeEscapeFallsBackGracefully() {
        String json = "{\"error_description\":\"bad \\uZZZZ escape\"}";
        String value = McpOAuthManager.extractJsonField(json, "error_description");
        assertThat(value).contains("uZZZZ");
    }
}
