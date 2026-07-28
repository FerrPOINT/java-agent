package com.azhukov.agent.bot.keyboard;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderKeyboardBuilderTest {

    private final ProviderKeyboardBuilder builder = new ProviderKeyboardBuilder();

    @Test
    void buildProviders_returnsButtons() {
        Map<String, String> providers = new LinkedHashMap<>();
        providers.put("openai", "OpenAI");
        providers.put("anthropic", "Anthropic");
        providers.put("google", "Google");
        providers.put("meta", "Meta");

        var rows = builder.buildProviders(providers);

        // 4 providers, 2 per row → 2 rows
        assertThat(rows).hasSize(2);

        // First row: OpenAI, Anthropic
        assertThat(rows.get(0)).hasSize(2);
        assertThat(rows.get(0).get(0).text()).isEqualTo("OpenAI");
        assertThat(rows.get(0).get(0).callbackData()).isEqualTo("pp:openai");
        assertThat(rows.get(0).get(1).text()).isEqualTo("Anthropic");
        assertThat(rows.get(0).get(1).callbackData()).isEqualTo("pp:anthropic");

        // Second row: Google, Meta
        assertThat(rows.get(1)).hasSize(2);
        assertThat(rows.get(1).get(0).text()).isEqualTo("Google");
        assertThat(rows.get(1).get(1).text()).isEqualTo("Meta");
    }

    @Test
    void buildModels_includesBackButton() {
        var rows = builder.buildModels(List.of("gpt-4", "gpt-3.5"));

        // 2 model buttons + 1 back button = 3 rows
        assertThat(rows).hasSize(3);

        assertThat(rows.get(0).get(0).text()).isEqualTo("gpt-4");
        assertThat(rows.get(1).get(0).text()).isEqualTo("gpt-3.5");

        // Last row is back button
        assertThat(rows.get(2).get(0).text()).contains("Back");
        assertThat(rows.get(2).get(0).callbackData()).isEqualTo("pp:back");
    }
}