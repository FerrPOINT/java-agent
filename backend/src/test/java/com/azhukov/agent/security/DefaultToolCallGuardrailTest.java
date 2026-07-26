package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolCallGuardrailTest {

    private AgentProperties props() {
        AgentProperties p = new AgentProperties();
        return p;
    }

    private GuardrailConfig cfg() {
        GuardrailConfig c = new GuardrailConfig();
        c.setWarningsEnabled(true);
        c.setHardStopEnabled(true);
        c.setWarnAfterExactFailure(2);
        c.setHardStopAfterExactFailure(4);
        c.setWarnAfterSameToolFailure(100);
        c.setHardStopAfterSameToolFailure(100);
        c.setWarnAfterIdempotentNoProgress(100);
        c.setHardStopAfterIdempotentNoProgress(100);
        return c;
    }

    @Test
    void beforeCallAllowsSafeToolAndArguments() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(props());
        assertThat(g.beforeCall("read_file", "{\"path\":\"/tmp/x\"}").isAllow()).isTrue();
    }

    @Test
    void beforeCallBlocksEmptyToolName() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(props());
        assertThat(g.beforeCall("", "{}").isAllow()).isFalse();
        assertThat(g.beforeCall(null, "{}").isAllow()).isFalse();
    }

    @Test
    void repeatedFailuresWarnThenHalt() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        GuardrailDecision warn = g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        assertThat(warn.action()).isEqualTo(GuardrailAction.WARN);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        GuardrailDecision halt = g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        assertThat(halt.action()).isEqualTo(GuardrailAction.HALT);
        assertThat(g.isHalted()).isTrue();
    }

    @Test
    void resetClearsState() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.reset();
        assertThat(g.isHalted()).isFalse();
    }

    @Test
    void beforeCallReturnsHaltWhenAlreadyHalted() {
        DefaultToolCallGuardrail g = new DefaultToolCallGuardrail(cfg());
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        g.afterCall("tool", "{}", ToolResult.fail("err"), true, null);
        GuardrailDecision d = g.beforeCall("read_file", "{}");
        assertThat(d.action()).isEqualTo(GuardrailAction.HALT);
    }
}
