package com.azhukov.agent.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailDecisionTest {

    @Test
    void allowDecisionHasNoMessageAndAllows() {
        GuardrailDecision d = GuardrailDecision.allow("tool");
        assertThat(d.action()).isEqualTo(GuardrailAction.ALLOW);
        assertThat(d.isAllow()).isTrue();
        assertThat(d.message()).isNull();
        assertThat(d.toolName()).isEqualTo("tool");
    }

    @Test
    void warnDecisionHasCorrectFields() {
        GuardrailDecision d = GuardrailDecision.warn("tool", "code", "be careful");
        assertThat(d.action()).isEqualTo(GuardrailAction.WARN);
        assertThat(d.isAllow()).isFalse();
        assertThat(d.message()).isEqualTo("be careful");
        assertThat(d.toolName()).isEqualTo("tool");
    }

    @Test
    void blockDecisionStopsExecution() {
        GuardrailDecision d = GuardrailDecision.block("tool", "forbidden", "not allowed");
        assertThat(d.action()).isEqualTo(GuardrailAction.BLOCK);
        assertThat(d.isAllow()).isFalse();
        assertThat(d.isBlockOrHalt()).isTrue();
        assertThat(d.message()).isEqualTo("not allowed");
    }

    @Test
    void haltDecisionStopsTurn() {
        GuardrailDecision d = GuardrailDecision.halt("tool", "halt", "too many warnings");
        assertThat(d.action()).isEqualTo(GuardrailAction.HALT);
        assertThat(d.isAllow()).isFalse();
        assertThat(d.isBlockOrHalt()).isTrue();
        assertThat(d.message()).isEqualTo("too many warnings");
    }
}
