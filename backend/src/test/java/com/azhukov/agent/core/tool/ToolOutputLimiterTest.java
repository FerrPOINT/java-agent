package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputLimiterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void truncate_failedResultWithDiagnosticContent_keepsFailureAndTruncatesContent() {
        properties.getToolOutput().setMaxChars(10);
        ToolResult result = limiter.truncate(new ToolResult(false, "1234567890ABCDEF", "exit 1"));

        assertThat(result.success()).isFalse();
        assertThat(result.content()).startsWith("1234");
        assertThat(result.content()).endsWith("ABCDEF");
        assertThat(result.content()).contains("OUTPUT TRUNCATED");
        assertThat(result.error()).isEqualTo("exit 1");
    }

    @Test
    void truncate_failedResultWithoutDiagnosticContent_returnsStructuredJsonError() throws Exception {
        properties.getToolOutput().setMaxChars(100);

        ToolResult result = limiter.truncate(ToolResult.fail("plain failure"));

        assertThat(result.success()).isFalse();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).isEqualTo("plain failure");
        assertThat(result.error()).isEqualTo("plain failure");
    }
}
