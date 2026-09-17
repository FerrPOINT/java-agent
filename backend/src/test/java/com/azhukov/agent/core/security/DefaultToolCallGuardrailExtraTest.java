package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extra branch-coverage tests for DefaultToolCallGuardrail.
 */
class DefaultToolCallGuardrailExtraTest {

    private GuardrailConfig cfg() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(2);
        c.setHardStopAfterExactFailure(10);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        c.setWarnAfterIdempotentNoProgress(100);
        c.setHardStopAfterIdempotentNoProgress(100);
        return c;
    }

    @Test
    void successResetsConsecutiveFailures() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // Two failures
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        // One success — should reset consecutiveFailures to 0
        g.afterCall("tool", "{}", ToolResult.ok("ok"), false, null);
        // One failure — consecutiveFailures should be 1, not 3
        GuardrailDecision d = g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        // consecutiveFailures=1, warnAfterExactFailure=2 → no warn yet
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }

    @Test
    void afterCallWithNullResultUsesUnknownError() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // null result, failed=true — should use "unknown" for error message
        g.afterCall("tool", "{}", null, true, null);
        // Should still track failure
        GuardrailDecision warn = g.afterCall("tool", "{}", null, true, null);
        assertThat(warn.action()).isEqualTo(GuardrailAction.WARN);
    }

    @Test
    void mixedFailuresDoNotHaltAndIdenticalFailureDoes() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(3);
        c.setHardStopAfterExactFailure(4);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        g.afterCall("browser_snapshot", "{}", ToolResult.fail("ConnectException"), true, null);
        g.afterCall("image_generate", "{}", ToolResult.fail("provider disabled"), true, null);
        g.afterCall("text_to_speech", "{}", ToolResult.fail("edge-tts unavailable"), true, null);
        assertThat(g.isHalted()).isFalse();

        g.afterCall("browser_snapshot", "{}", ToolResult.fail("ConnectException"), true, null);
        GuardrailDecision warn = g.afterCall("browser_snapshot", "{}", ToolResult.fail("ConnectException"), true, null);
        assertThat(warn.action()).isEqualTo(GuardrailAction.WARN);
        GuardrailDecision halt = g.afterCall("browser_snapshot", "{}", ToolResult.fail("ConnectException"), true, null);
        assertThat(halt.action()).isEqualTo(GuardrailAction.HALT);
        assertThat(g.isHalted()).isTrue();
        assertThat(g.haltMessage(null)).contains("browser_snapshot").contains("same reason");
    }

    @Test
    void hardStopDisabledDoesNotHalt() {
        GuardrailConfig c = cfg();
        c.setHardStopEnabled(false);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        for (int i = 0; i < 20; i++) {
            g.afterCall("tool", "{}", ToolResult.fail("err" + i), true, null);
        }
        // Should never halt because hardStop is disabled
        assertThat(g.isHalted()).isFalse();
    }

    @Test
    void warningsDisabledDoesNotWarn() {
        GuardrailConfig c = cfg();
        c.setWarningsEnabled(false);
        c.setHardStopAfterExactFailure(3);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        // Same failure twice, warnings disabled → no warning yet.
        GuardrailDecision d = g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        // Same failure three times, hardStopAfterExactFailure=3 → HALT.
        assertThat(d.action()).isEqualTo(GuardrailAction.HALT);
    }

    @Test
    void threeArgAfterCallDelegatesToFiveArg() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        // 3-arg overload should delegate to 5-arg
        GuardrailDecision d = g.afterCall("tool", "{}", ToolResult.ok("ok"), false);
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
    }

    @Test
    void historyTrimsAfter20Calls() {
        GuardrailConfig c = cfg();
        c.setWarnAfterExactFailure(100);
        c.setHardStopAfterExactFailure(100);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(c);

        // Call 25 times with success — history should trim to 20
        for (int i = 0; i < 25; i++) {
            g.afterCall("tool" + i, "{}", ToolResult.ok("ok"), false, null);
        }
        // Should still function correctly
        GuardrailDecision d = g.beforeCall("read_file", "{}");
        assertThat(d.isAllow()).isTrue();
    }
}