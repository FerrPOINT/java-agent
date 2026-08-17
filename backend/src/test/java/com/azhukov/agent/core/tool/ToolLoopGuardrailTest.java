package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 9: Tool loop guardrails test.
 * Verifies repeated tool calls trigger warnings at the right thresholds.
 */
class ToolLoopGuardrailTest {

    @Test
    void noWarningOnFirstCall() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);
        String warning = guardrail.beforeCall("terminal", "{\"command\":\"ls\"}");
        assertThat(warning).isNull();
    }

    @Test
    void warningAfterMaxExactRepeats() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);
        String args = "{\"command\":\"ls\"}";

        // First 2 calls: no warning
        guardrail.afterCall("terminal", args, true);
        guardrail.afterCall("terminal", args, true);

        // 3rd failure: should trigger warning
        String warning = guardrail.afterCall("terminal", args, true);
        assertThat(warning).isNotNull();
        assertThat(warning).contains("terminal");
        assertThat(warning).contains("identical arguments");
    }

    @Test
    void strongWarningAfterMaxSameToolFailures() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);

        // Different arguments each time to avoid exact-repeat trigger
        for (int i = 0; i < 4; i++) {
            guardrail.afterCall("terminal", "{\"command\":\"cmd" + i + "\"}", true);
        }

        // 5th failure with different args: should trigger same-tool warning
        String warning = guardrail.afterCall("terminal", "{\"command\":\"cmd5\"}", true);
        assertThat(warning).isNotNull();
        assertThat(warning).contains("failed");
    }

    @Test
    void successResetsFailureCounts() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);
        String args = "{\"command\":\"ls\"}";

        // Two failures
        guardrail.afterCall("terminal", args, true);
        guardrail.afterCall("terminal", args, true);

        // Success resets the count
        guardrail.afterCall("terminal", args, false);

        // Another failure should not trigger (count was reset)
        String warning = guardrail.afterCall("terminal", args, true);
        assertThat(warning).isNull();
    }

    @Test
    void disabledGuardrailReturnsNoWarning() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(false, 3, 5);
        String args = "{\"command\":\"ls\"}";

        // Many failures — should still return null because disabled
        for (int i = 0; i < 10; i++) {
            guardrail.afterCall("terminal", args, true);
        }

        String warning = guardrail.afterCall("terminal", args, true);
        assertThat(warning).isNull();
    }

    @Test
    void resetForTurnClearsCounts() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);
        String args = "{\"command\":\"ls\"}";

        // Two failures
        guardrail.afterCall("terminal", args, true);
        guardrail.afterCall("terminal", args, true);

        // Reset
        guardrail.resetForTurn();

        // Should not trigger after reset
        String warning = guardrail.afterCall("terminal", args, true);
        assertThat(warning).isNull();
    }

    @Test
    void differentToolsTrackedSeparately() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 3, 5);

        // Fail terminal 2 times
        guardrail.afterCall("terminal", "{\"command\":\"a\"}", true);
        guardrail.afterCall("terminal", "{\"command\":\"b\"}", true);

        // Fail read_file 2 times
        guardrail.afterCall("read_file", "{\"path\":\"a\"}", true);
        guardrail.afterCall("read_file", "{\"path\":\"b\"}", true);

        // Neither should trigger yet
        String termWarning = guardrail.afterCall("terminal", "{\"command\":\"c\"}", true);
        String fileWarning = guardrail.afterCall("read_file", "{\"path\":\"c\"}", true);

        // terminal has 3 same-tool failures, read_file has 3 same-tool failures
        // Both have fewer than maxSameToolFailures (5) and fewer than maxExactRepeats (3) exact
        // Actually terminal has 3 same-tool failures, should not trigger same-tool (needs 5)
        // And different args each time, so no exact repeat trigger
        assertThat(termWarning).isNull();
        assertThat(fileWarning).isNull();
    }

    @Test
    void appendWarningFormatsCorrectly() {
        String result = ToolLoopGuardrail.appendWarning("tool output", "loop detected");
        assertThat(result).contains("tool output");
        assertThat(result).contains("[Tool loop guardrail:");
        assertThat(result).contains("loop detected");
    }

    @Test
    void appendWarningWithNullResult() {
        String result = ToolLoopGuardrail.appendWarning(null, "warning");
        assertThat(result).contains("[Tool loop guardrail:");
    }

    @Test
    void appendWarningWithNullWarning() {
        String result = ToolLoopGuardrail.appendWarning("output", null);
        assertThat(result).isEqualTo("output");
    }
}