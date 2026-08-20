package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeFooterTest {

    private BotProperties properties;
    private RuntimeFooter footer;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        footer = new RuntimeFooter(properties);
    }

    @Test
    void format_disabled_returnsEmpty() {
        properties.getFooter().setEnabled(false);
        String result = footer.format("moonshotai/kimi-k2.6", 5000, 20000, "/home/user/work");
        assertThat(result).isEmpty();
    }

    @Test
    void format_allFields_producesFullFooter() {
        properties.getFooter().setEnabled(true);
        // Set working directory to something we can predict
        properties.setWorkingDirectory("/home/user/work");
        // We need to mock System.getProperty("user.home") — instead use a path that doesn't start with home
        String result = footer.format("moonshotai/kimi-k2.6", 5000, 20000, "/tmp/work");
        assertThat(result).contains("kimi-k2.6");
        assertThat(result).contains("25%");
        assertThat(result).contains("/tmp/work");
        assertThat(result).contains(" · ");
    }

    @Test
    void format_shortModelName_dropsVendorPrefix() {
        properties.getFooter().setEnabled(true);
        String result = footer.format("anthropic/claude-sonnet-4", 1000, 10000, "/tmp");
        assertThat(result).contains("claude-sonnet-4");
        assertThat(result).doesNotContain("anthropic/claude-sonnet-4");
    }

    @Test
    void format_contextPct_calculatesPercentage() {
        properties.getFooter().setEnabled(true);
        // 5000 / 20000 = 25%
        String result = footer.format("model", 5000, 20000, "/tmp");
        assertThat(result).contains("25%");

        // 0 / 1000 = 0%
        result = footer.format("model", 0, 1000, "/tmp");
        assertThat(result).contains("0%");

        // 10000 / 10000 = 100%
        result = footer.format("model", 10000, 10000, "/tmp");
        assertThat(result).contains("100%");
    }

    @Test
    void format_cwd_replacesHomeWithTilde() {
        properties.getFooter().setEnabled(true);
        String home = System.getProperty("user.home");
        String cwd = home + "/work";
        String result = footer.format("model", 0, 1000, cwd);
        assertThat(result).contains("~/work");
    }
}