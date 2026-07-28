package com.azhukov.agent.bot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayConfigTest {

    @Test
    void resolveToolProgress_returnsGlobalDefault() {
        BotProperties properties = new BotProperties();
        properties.getDisplay().setToolProgress("compact");

        DisplayConfig config = new DisplayConfig(properties);
        assertThat(config.resolveToolProgress("telegram")).isEqualTo("compact");
    }

    @Test
    void resolvePreviewLength_returnsGlobalDefault() {
        BotProperties properties = new BotProperties();
        properties.getDisplay().setPreviewLength(300);

        DisplayConfig config = new DisplayConfig(properties);
        assertThat(config.resolvePreviewLength("telegram")).isEqualTo(300);
    }
}