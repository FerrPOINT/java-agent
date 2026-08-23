package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputLimiterTest {

    private final AgentProperties properties = new AgentProperties();
    private final ToolOutputLimiter limiter = new ToolOutputLimiter(properties);

    @Test
    void truncate_shortContent_returnsAsIs() {
        String content = "short content";
        String result = limiter.truncate(content, 1000);
        assertThat(result).isEqualTo(content);
    }

    @Test
    void truncate_longContent_keepsHeadAndTail() {
        // Hermes parity (terminal_tool.py:3451): 40% head + 60% tail + notice
        String head = "H".repeat(300);
        String tail = "T".repeat(300);
        String content = head + "M".repeat(400) + tail; // 1000 chars, cap 200
        String result = limiter.truncate(content, 200);
        assertThat(result).contains("OUTPUT TRUNCATED - 800 chars omitted out of 1000 total");
        assertThat(result).startsWith("H".repeat(80));   // 40% of 200
        assertThat(result).endsWith("T".repeat(120));    // 60% of 200
        // the middle is gone
        assertThat(result).doesNotContain("M");
    }

    @Test
    void truncate_longContent_tailKeepsRecentOutput() {
        // the tail must survive: exit codes / test summaries ride the end
        String content = "A".repeat(900) + "EXIT=0";
        String result = limiter.truncate(content, 100);
        assertThat(result).endsWith("EXIT=0");
    }

    @Test
    void truncate_nullContent_returnsNull() {
        String result = limiter.truncate(null, 1000);
        assertThat(result).isNull();
    }
}