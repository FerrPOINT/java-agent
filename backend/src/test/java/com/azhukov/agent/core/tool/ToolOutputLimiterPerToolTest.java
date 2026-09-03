package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rev-42: agent.tool-output.* per-tool keys were declared in application.yml
 * but never bound (orphaned) — operators could set env vars with no effect.
 * Now bound; verify per-tool overrides and zero-fallback semantics.
 */
class ToolOutputLimiterPerToolTest {

    private AgentProperties properties;
    private ToolOutputLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        limiter = new ToolOutputLimiter(properties);
    }

    @Test
    void terminalOverrideApplies() {
        properties.getToolOutput().setMaxChars(1000);
        properties.getToolOutput().setTerminalMaxChars(100);
        ToolResult longResult = ToolResult.ok("x".repeat(500));
        ToolResult truncated = limiter.truncate(longResult, "terminal");
        assertThat(truncated.content().length()).isLessThan(200);
        assertThat(truncated.content()).contains("chars omitted");
    }

    @Test
    void zeroOverrideFallsBackToGeneric() {
        properties.getToolOutput().setMaxChars(100);
        properties.getToolOutput().setTerminalMaxChars(0);
        ToolResult longResult = ToolResult.ok("x".repeat(500));
        ToolResult truncated = limiter.truncate(longResult, "terminal");
        assertThat(truncated.content().length()).isLessThan(200);
    }

    @Test
    void webExtractOverrideApplies() {
        properties.getToolOutput().setMaxChars(10000);
        properties.getToolOutput().setWebExtractMaxChars(80);
        ToolResult longResult = ToolResult.ok("x".repeat(500));
        ToolResult truncated = limiter.truncate(longResult, "web_extract");
        assertThat(truncated.content().length()).isLessThan(200);
    }

    @Test
    void otherToolsUseGenericLimit() {
        properties.getToolOutput().setMaxChars(100);
        properties.getToolOutput().setTerminalMaxChars(5000);
        ToolResult longResult = ToolResult.ok("x".repeat(400));
        // read_file is not terminal — terminal override must NOT apply
        ToolResult truncated = limiter.truncate(longResult, "read_file");
        assertThat(truncated.content().length()).isLessThan(250);
    }
}
