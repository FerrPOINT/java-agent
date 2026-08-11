package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link DefaultToolCallGuardrail}.
 * Covers null result handling, error message tracking, and consecutive failure resets.
 */
class DefaultToolCallGuardrailBranchTest2 {

    private GuardrailConfig cfg() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(2);
        c.setHardStopAfterExactFailure(5);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        c.setWarnAfterIdempotentNoProgress(100);
        c.setHardStopAfterIdempotentNoProgress(100);
        return c;
    }

    @Test
    void beforeCall_blankToolName_blocks() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        GuardrailDecision d = g.beforeCall("  ", "{}");
        assertThat(d.isBlockOrHalt()).isTrue();
        assertThat(d.action()).isEqualTo(GuardrailAction.BLOCK);
    }

    @Test
    void afterCall_nullResult_failed_tracksError() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // First failure with null result
        g.afterCall("tool", "{}", null, true, null);
        // Second failure with null result — should trigger warn at warnAfter=2
        GuardrailDecision d = g.afterCall("tool", "{}", null, true, null);
        assertThat(d.action()).isEqualTo(GuardrailAction.WARN);
    }

    @Test
    void afterCall_nullResultAndError_notFailed_succeeds() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        GuardrailDecision d = g.afterCall("tool", "{}", null, false, null);
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }

    @Test
    void afterCall_resultWithError_nullError_usesUnknown() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // ToolResult with null error and failed=true
        ToolResult result = new ToolResult(false, "some content", null);
        g.afterCall("tool", "{}", result, true, null);
        GuardrailDecision d = g.afterCall("tool", "{}", result, true, null);
        assertThat(d.action()).isEqualTo(GuardrailAction.WARN);
    }

    @Test
    void afterCall_successResetsConsecutiveFailures_noWarn() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // 1 failure
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        // Success resets
        g.afterCall("tool", "{}", ToolResult.ok("ok"), false, null);
        // 1 failure again — should not warn (warnAt=2)
        GuardrailDecision d = g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }

    @Test
    void afterCall_successClearsRecentErrorMessages() {
        GuardrailConfig c = cfg();
        c.setHardStopAfterExactFailure(100);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);
        // Two failures
        g.afterCall("tool", "{}", ToolResult.fail("err1"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err2"), true, null);
        // Success — should clear error messages
        g.afterCall("tool", "{}", ToolResult.ok("ok"), false, null);
        // New failure — should start from 1
        GuardrailDecision d = g.afterCall("tool", "{}", ToolResult.fail("err3"), true, null);
        // warnAt=2, so 1 failure after reset → ALLOW
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }

    @Test
    void recentToolNames_trimmedAfter10Entries() {
        GuardrailConfig c = cfg();
        c.setHardStopAfterExactFailure(100);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);
        for (int i = 0; i < 15; i++) {
            g.afterCall("tool" + i, "{}", ToolResult.ok("ok"), false, null);
        }
        // Should still function after 15 calls
        GuardrailDecision d = g.beforeCall("any_tool", "{}");
        assertThat(d.isAllow()).isTrue();
    }

    @Test
    void recentErrorMessages_trimmedAfter10Entries() {
        GuardrailConfig c = cfg();
        c.setWarnAfterExactFailure(100);
        c.setHardStopAfterExactFailure(100);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        c.setWarnAfterIdempotentNoProgress(100);
        c.setHardStopAfterIdempotentNoProgress(100);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);
        for (int i = 0; i < 15; i++) {
            g.afterCall("tool", "{}", ToolResult.fail("err" + i), true, null);
        }
        // Should not crash, should not halt
        assertThat(g.isHalted()).isFalse();
    }

    @Test
    void constructor_withAgentProperties_doesNotThrow() {
        AgentProperties props = new AgentProperties();
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(props);
        assertThat(g.isHalted()).isFalse();
    }

    @Test
    void constructor_withNullAgentProperties_doesNotThrow() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail((AgentProperties) null);
        assertThat(g.isHalted()).isFalse();
    }

    @Test
    void idempotentNoProgress_withNullResultContent_doesNotTrigger() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(100);
        c.setHardStopAfterExactFailure(100);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        c.setWarnAfterIdempotentNoProgress(2);
        c.setHardStopAfterIdempotentNoProgress(3);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        // null result → content is null, should match null content from prior calls
        g.afterCall("tool", "{}", null, true, null);
        GuardrailDecision warn = g.afterCall("tool", "{}", null, true, null);
        assertThat(warn.action()).isEqualTo(GuardrailAction.WARN);
    }

    @Test
    void sameToolFailure_differentTools_trackSeparately() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(100);
        c.setHardStopAfterExactFailure(100);
        c.setWarnAfterSameToolFailure(3);
        c.setHardStopAfterSameToolFailure(5);
        c.setWarnAfterIdempotentNoProgress(100);
        c.setHardStopAfterIdempotentNoProgress(100);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        // 2 failures on toolA — sameToolFailureCount for A = 2, below warn=3
        g.afterCall("toolA", "{}", ToolResult.fail("errA1"), true, null);
        g.afterCall("toolA", "{}", ToolResult.fail("errA2"), true, null);
        // 1 failure on toolB — sameToolFailureCount for B = 1, below warn=3
        GuardrailDecision d = g.afterCall("toolB", "{}", ToolResult.fail("errB"), true, null);
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }
}