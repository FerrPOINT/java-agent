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
    void truncate_longContent_truncatesWithWarning() {
        String content = "x".repeat(500);
        String result = limiter.truncate(content, 100);
        assertThat(result).hasSize(100 + "\n[output truncated at 100 chars]".length());
        assertThat(result).startsWith("x".repeat(100));
        assertThat(result).endsWith("[output truncated at 100 chars]");
    }

    @Test
    void truncate_nullContent_returnsNull() {
        String result = limiter.truncate(null, 1000);
        assertThat(result).isNull();
    }
}