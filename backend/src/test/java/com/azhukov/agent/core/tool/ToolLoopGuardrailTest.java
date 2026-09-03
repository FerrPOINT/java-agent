package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 9: Tool loop guardrails test.
 * Verifies repeated tool calls trigger warnings at the right thresholds.
 */
class ToolLoopGuardrailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void exactRepeatUsesCanonicalJsonArguments() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 10);

        guardrail.afterCall("terminal", "{\"command\":\"ls\",\"timeout\":10}", true);
        String warning = guardrail.afterCall("terminal", "{\"timeout\":10,\"command\":\"ls\"}", true);

        assertThat(warning).isNotNull();
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


    // ── Hermes parity: idempotent no-progress detection ─────────────────

    @Test
    void idempotentSameResultWarnsOnSecondSuccess() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);
        String args = "{\"path\":\"README.md\"}";

        assertThat(guardrail.afterCall("read_file", args, "same content", false)).isNull();
        String warning = guardrail.afterCall("read_file", args, "same content", false);

        assertThat(warning).contains("read_file");
        assertThat(warning).contains("same result 2 times");
    }

    @Test
    void idempotentNoProgressUsesCanonicalJsonArguments() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);

        assertThat(guardrail.afterCall(
            "read_file",
            "{\"path\":\"README.md\",\"limit\":100}",
            "same content",
            false
        )).isNull();
        String warning = guardrail.afterCall(
            "read_file",
            "{\"limit\":100,\"path\":\"README.md\"}",
            "same content",
            false
        );

        assertThat(warning).isNotNull();
        assertThat(warning).contains("same result 2 times");
    }

    @Test
    void idempotentNoProgressUsesCanonicalJsonResult() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);
        String args = "{\"path\":\"README.md\"}";

        assertThat(guardrail.afterCall("read_file", args, "{\"a\":1,\"b\":2}", false)).isNull();
        String warning = guardrail.afterCall("read_file", args, "{\"b\":2,\"a\":1}", false);

        assertThat(warning).isNotNull();
        assertThat(warning).contains("same result 2 times");
    }

    @Test
    void idempotentChangedResultResetsNoProgressCount() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);
        String args = "{\"path\":\"README.md\"}";

        guardrail.afterCall("read_file", args, "version one", false);
        assertThat(guardrail.afterCall("read_file", args, "version two", false)).isNull();
    }

    @Test
    void mutatingSameResultDoesNotTriggerNoProgressWarning() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 50);
        String args = "{\"path\":\"config.yml\"}";

        assertThat(guardrail.afterCall("write_file", args, "written", false)).isNull();
        assertThat(guardrail.afterCall("write_file", args, "written", false)).isNull();
    }

    // ── Hermes parity: LoopCapConfig ────────────────────────────────────

    @Test
    void webSearchCapBlocksCapPlusOneCall() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 2, 50);

        assertThat(guardrail.beforeCall("web_search", "{\"query\":\"one\"}")).isNull();
        assertThat(guardrail.beforeCall("web_search", "{\"query\":\"two\"}")).isNull();
        String blocked = guardrail.beforeCall("web_search", "{\"query\":\"three\"}");

        assertThat(blocked).startsWith("Blocked web_search");
        assertThat(blocked).contains("2 web searches");
    }

    @Test
    void delegateTaskCapBlocksCapPlusOneCall() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 1);

        assertThat(guardrail.beforeCall("delegate_task", "{\"goal\":\"one\"}")).isNull();
        String blocked = guardrail.beforeCall("delegate_task", "{\"goal\":\"two\"}");

        assertThat(blocked).startsWith("Blocked delegate_task");
        assertThat(blocked).contains("limit 1");
    }

    @Test
    void delegateTaskBatchCountsEverySpawnedSubagent() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 3);

        assertThat(guardrail.beforeCall(
            "delegate_task",
            "{\"tasks\":[{\"goal\":\"one\"},{\"goal\":\"two\"}]}"
        )).isNull();
        assertThat(guardrail.beforeCall("delegate_task", "{\"goal\":\"three\"}")).isNull();
        String blocked = guardrail.beforeCall("delegate_task", "{\"goal\":\"four\"}");

        assertThat(blocked).startsWith("Blocked delegate_task");
        assertThat(blocked).contains("3 subagents");
        assertThat(blocked).contains("limit 3");
    }

    @Test
    void delegateTaskControlActionsDoNotConsumeOrBlockSubagentCap() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 50, 1);

        assertThat(guardrail.beforeCall("delegate_task", "{\"goal\":\"one\"}")).isNull();
        assertThat(guardrail.beforeCall("delegate_task", "{\"action\":\"list\"}")).isNull();
        assertThat(guardrail.beforeCall(
            "delegate_task",
            "{\"action\":\"steer\",\"subagent_id\":\"s1\",\"message\":\"focus\"}"
        )).isNull();
        assertThat(guardrail.beforeCall("delegate_task", "{\"action\":\"stop\",\"subagent_id\":\"s1\"}")).isNull();
        String blocked = guardrail.beforeCall("delegate_task", "{\"goal\":\"two\"}");

        assertThat(blocked).startsWith("Blocked delegate_task");
        assertThat(blocked).contains("1 subagents");
        assertThat(blocked).contains("limit 1");
    }

    @Test
    void resetForTurnResetsRunawayCaps() {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 1, 1);
        guardrail.beforeCall("web_search", "{\"query\":\"one\"}");
        assertThat(guardrail.beforeCall("web_search", "{\"query\":\"two\"}")).startsWith("Blocked");

        guardrail.resetForTurn();

        assertThat(guardrail.beforeCall("web_search", "{\"query\":\"three\"}")).isNull();
    }

    @Test
    void blockedResultCarriesStructuredFailureAndErrorMetadata() throws Exception {
        ToolLoopGuardrail guardrail = new ToolLoopGuardrail(true, 2, 3, 2, 1, 1);
        String message = "Blocked web_search after 1 web searches";

        ToolResult result = guardrail.blockedResult("web_search", "{\"query\":\"two\"}", message);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo(message);
        JsonNode payload = MAPPER.readTree(result.content());
        assertThat(payload.path("success").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isEqualTo(message);
        assertThat(payload.path("guardrail").path("action").asText()).isEqualTo("block");
        assertThat(payload.path("guardrail").path("code").asText()).isEqualTo("loop_web_search_cap");
    }

}
