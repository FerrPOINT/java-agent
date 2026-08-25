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

    // ─── Latency support (Hermes parity) ───────────────────────────

    @Test
    void format_withLatency_includesHumanizedDuration() {
        properties.getFooter().setEnabled(true);
        properties.getFooter().getFields().add("latency");
        String result = footer.format("model", 1000, 10000, "/tmp", 22);
        assertThat(result).contains("22s");
    }

    @Test
    void format_withLatency_under1s() {
        properties.getFooter().setEnabled(true);
        properties.getFooter().getFields().clear();
        properties.getFooter().getFields().add("latency");
        String result = footer.format("model", 0, 1000, "/tmp", 0);
        assertThat(result).contains("<1s");
    }

    @Test
    void format_withLatency_over1m() {
        properties.getFooter().setEnabled(true);
        properties.getFooter().getFields().clear();
        properties.getFooter().getFields().add("latency");
        String result = footer.format("model", 0, 1000, "/tmp", 125);
        assertThat(result).contains("2m05s");
    }

    @Test
    void format_negativeLatency_skipped() {
        properties.getFooter().setEnabled(true);
        properties.getFooter().getFields().clear();
        properties.getFooter().getFields().add("latency");
        properties.getFooter().getFields().add("model");
        String result = footer.format("model", 0, 1000, "/tmp", -1);
        // latency should be skipped, only model included
        assertThat(result).contains("model");
        assertThat(result).doesNotContain("s");
    }

    @Test
    void formatLatency_variousValues() {
        assertThat(RuntimeFooter.formatLatency(0)).isEqualTo("<1s");
        assertThat(RuntimeFooter.formatLatency(1)).isEqualTo("1s");
        assertThat(RuntimeFooter.formatLatency(59)).isEqualTo("59s");
        assertThat(RuntimeFooter.formatLatency(60)).isEqualTo("1m00s");
        assertThat(RuntimeFooter.formatLatency(125)).isEqualTo("2m05s");
        assertThat(RuntimeFooter.formatLatency(3661)).isEqualTo("61m01s");
    }
}