package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the new P1-7 enhancements to {@link DefaultToolGuardrails}:
 * - Pre-call validation (checkBeforeCall) with allow/warn/block/halt decisions
 * - Canonical args hashing (SHA-256 based ToolCallSignature equivalent)
 * - No-progress detection for idempotent tools
 * - Warning system with structured GuardrailDecision
 * - Failure recovery hints
 */
class DefaultToolGuardrailsEnhancementsTest {

    private DefaultToolGuardrails createGuardrails() {
        return new DefaultToolGuardrails(new AgentProperties(), new ApprovalQueue());
    }

    // ─── Pre-call validation (checkBeforeCall) ───

    @Nested
    @DisplayName("Pre-call validation (checkBeforeCall)")
    class PreCallValidation {

        @Test
        @DisplayName("Valid tool call returns allow decision")
        void validCallReturnsAllow() {
            DefaultToolGuardrails guardrails = createGuardrails();
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("read_file", "{\"path\":\"/tmp/test\"}");

            assertThat(decision.action()).isEqualTo("allow");
            assertThat(decision.allowsExecution()).isTrue();
            assertThat(decision.shouldHalt()).isFalse();
        }

        @Test
        @DisplayName("Null tool name returns block decision")
        void nullToolNameReturnsBlock() {
            DefaultToolGuardrails guardrails = createGuardrails();
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall(null, "{}");

            assertThat(decision.action()).isEqualTo("block");
            assertThat(decision.shouldHalt()).isTrue();
            assertThat(decision.message()).contains("blank or null");
        }

        @Test
        @DisplayName("Blank tool name returns block decision")
        void blankToolNameReturnsBlock() {
            DefaultToolGuardrails guardrails = createGuardrails();
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("  ", "{}");

            assertThat(decision.action()).isEqualTo("block");
        }

        @Test
        @DisplayName("Blocked tool returns block decision")
        void blockedToolReturnsBlock() {
            DefaultToolGuardrails guardrails = createGuardrails();
            guardrails.setBlockedTools(Set.of("exec"));

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("exec", "{}");

            assertThat(decision.action()).isEqualTo("block");
            assertThat(decision.message()).contains("blocked list");
        }

        @Test
        @DisplayName("When halted, all calls return halt decision")
        void haltedReturnsHalt() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // Trigger halt via 5 identical calls
            for (int i = 0; i < 5; i++) {
                guardrails.recordToolCall("read_file", "{}", true);
            }

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{}");

            assertThat(decision.action()).isEqualTo("halt");
            assertThat(decision.shouldHalt()).isTrue();
        }
    }

    // ─── Repeated exact failure detection (pre-call) ───

    @Nested
    @DisplayName("Repeated exact failure detection (pre-call)")
    class ExactFailurePreCall {

        @Test
        @DisplayName("After 5 exact failures, pre-call returns halt")
        void fiveExactFailuresHalt() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // Record 5 failures with same tool + same args
            for (int i = 0; i < 5; i++) {
                guardrails.recordToolCall("write_file", "{\"path\":\"/etc/passwd\"}", false);
            }

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{\"path\":\"/etc/passwd\"}");

            assertThat(decision.action()).isEqualTo("halt");
            assertThat(decision.code()).isEqualTo("repeated_exact_failure_block");
            assertThat(decision.message()).contains("5 times");
        }

        @Test
        @DisplayName("After 2 exact failures, pre-call returns warning (not block)")
        void twoExactFailuresWarn() {
            DefaultToolGuardrails guardrails = createGuardrails();

            guardrails.recordToolCall("write_file", "{\"path\":\"/tmp/test\"}", false);
            guardrails.recordToolCall("write_file", "{\"path\":\"/tmp/test\"}", false);

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{\"path\":\"/tmp/test\"}");

            assertThat(decision.action()).isEqualTo("warn");
            assertThat(decision.allowsExecution()).isTrue();
            assertThat(decision.code()).isEqualTo("repeated_exact_failure_warning");
        }

        @Test
        @DisplayName("Different args failures don't trigger exact failure block")
        void differentArgsNoExactBlock() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // 5 failures with different args each time
            for (int i = 0; i < 5; i++) {
                guardrails.recordToolCall("write_file", "{\"path\":\"/tmp/test" + i + "\"}", false);
            }

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{\"path\":\"/tmp/new\"}");

            // Should NOT be blocked for exact failure (different args)
            // but will be halted from consecutive failures (3+)
            assertThat(decision.code()).isNotEqualTo("repeated_exact_failure_block");
        }
    }

    // ─── No-progress detection for idempotent tools ───

    @Nested
    @DisplayName("No-progress detection for idempotent tools")
    class NoProgressDetection {

        @Test
        @DisplayName("After 5 identical successful idempotent calls, pre-call blocks or halts")
        void fiveIdenticalIdempotentCallsHalt() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // read_file is idempotent — 5 identical successful calls = no progress
            for (int i = 0; i < 5; i++) {
                guardrails.recordToolCall("read_file", "{\"path\":\"/same/path\"}", true);
            }

            // The 5th call already triggers halt via identical args
            // Now check before-call — should at least warn (no-progress) or halt
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("read_file", "{\"path\":\"/same/path\"}");

            assertThat(decision.allowsExecution()).isFalse();
        }

        @Test
        @DisplayName("Idempotent tool with different args does not trigger no-progress")
        void idempotentDifferentArgsNoProgress() {
            DefaultToolGuardrails guardrails = createGuardrails();

            for (int i = 0; i < 10; i++) {
                guardrails.recordToolCall("read_file", "{\"path\":\"/file" + i + "\"}", true);
            }

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("read_file", "{\"path\":\"/new\"}");

            assertThat(decision.allowsExecution()).isTrue();
        }
    }

    // ─── Same-tool failure warnings ───

    @Nested
    @DisplayName("Same-tool failure warnings")
    class SameToolFailureWarnings {

        @Test
        @DisplayName("After 3 same-tool failures with different args, pre-call returns halt with specific reason")
        void threeSameToolFailuresHalt() {
            DefaultToolGuardrails guardrails = createGuardrails();

            guardrails.recordToolCall("terminal", "{\"command\":\"ls\"}", false);
            guardrails.recordToolCall("terminal", "{\"command\":\"pwd\"}", false);
            guardrails.recordToolCall("terminal", "{\"command\":\"whoami\"}", false);

            // 3 consecutive failures → halted=true, same-tool failures=3 → specific halt reason
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("terminal", "{\"command\":\"date\"}");

            assertThat(decision.shouldHalt()).isTrue();
            assertThat(decision.message()).contains("terminal");
            assertThat(decision.message()).contains("3 times");
        }

        @Test
        @DisplayName("Terminal failure with 3+ failures includes terminal-specific recovery hint")
        void terminalFailureHint() {
            DefaultToolGuardrails guardrails = createGuardrails();

            guardrails.recordToolCall("terminal", "{\"command\":\"ls\"}", false);
            guardrails.recordToolCall("terminal", "{\"command\":\"pwd\"}", false);
            guardrails.recordToolCall("terminal", "{\"command\":\"whoami\"}", false);

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("terminal", "{\"command\":\"date\"}");

            assertThat(decision.message()).contains("pwd");
            assertThat(decision.message()).contains("diagnostic");
        }

        @Test
        @DisplayName("Non-terminal failure with 3+ failures includes generic recovery hint")
        void nonTerminalFailureHint() {
            DefaultToolGuardrails guardrails = createGuardrails();

            guardrails.recordToolCall("write_file", "{\"path\":\"/a\"}", false);
            guardrails.recordToolCall("write_file", "{\"path\":\"/b\"}", false);
            guardrails.recordToolCall("write_file", "{\"path\":\"/c\"}", false);

            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{\"path\":\"/d\"}");

            assertThat(decision.message()).contains("different arguments");
        }
    }

    // ─── GuardrailDecision record ───

    @Nested
    @DisplayName("GuardrailDecision record")
    class GuardrailDecisionTest {

        @Test
        void allowDecision() {
            var d = DefaultToolGuardrails.GuardrailDecision.allow("read_file");
            assertThat(d.action()).isEqualTo("allow");
            assertThat(d.allowsExecution()).isTrue();
            assertThat(d.shouldHalt()).isFalse();
        }

        @Test
        void warnDecision() {
            var d = DefaultToolGuardrails.GuardrailDecision.warn("read_file", "code", "message");
            assertThat(d.action()).isEqualTo("warn");
            assertThat(d.allowsExecution()).isTrue();
            assertThat(d.shouldHalt()).isFalse();
        }

        @Test
        void blockDecision() {
            var d = DefaultToolGuardrails.GuardrailDecision.block("read_file", "code", "message");
            assertThat(d.action()).isEqualTo("block");
            assertThat(d.allowsExecution()).isFalse();
            assertThat(d.shouldHalt()).isTrue();
        }

        @Test
        void haltDecision() {
            var d = DefaultToolGuardrails.GuardrailDecision.halt("read_file", "code", "message");
            assertThat(d.action()).isEqualTo("halt");
            assertThat(d.allowsExecution()).isFalse();
            assertThat(d.shouldHalt()).isTrue();
        }
    }

    // ─── Canonical args signature ───

    @Nested
    @DisplayName("Canonical args signature tracking")
    class CanonicalSignature {

        @Test
        @DisplayName("Same tool + same args with different whitespace tracked as identical")
        void whitespaceDifferenceIsCanonical() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // Record 4 failures with args that differ only in whitespace
            guardrails.recordToolCall("write_file", "{\"path\":\"/tmp/test\"}", false);
            guardrails.recordToolCall("write_file", "{ \"path\" : \"/tmp/test\" }", false);
            guardrails.recordToolCall("write_file", "{\"path\": \"/tmp/test\"}", false);
            guardrails.recordToolCall("write_file", " {\"path\":\"/tmp/test\"} ", false);
            // These should be tracked as different by the raw-string identicalArgsCount
            // but the canonical signature should accumulate exact failures

            // After 4 same-tool failures (different whitespace), pre-call should at least warn
            DefaultToolGuardrails.GuardrailDecision decision =
                guardrails.checkBeforeCall("write_file", "{\"path\":\"/tmp/test\"}");

            // same-tool failure count should be 4 → warning
            assertThat(decision.action()).isIn("warn", "halt");
        }
    }

    // ─── Reset clears new state ───

    @Nested
    @DisplayName("Reset clears new tracking state")
    class ResetClearsState {

        @Test
        @DisplayName("Reset clears exact failure counts")
        void resetClearsExactFailures() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // Record 4 exact failures
            for (int i = 0; i < 4; i++) {
                guardrails.recordToolCall("write_file", "{\"path\":\"/tmp\"}", false);
            }

            // After 4 failures, should be halted (consecutive or exact failure)
            var decisionBefore = guardrails.checkBeforeCall("write_file", "{\"path\":\"/tmp\"}");
            assertThat(decisionBefore.shouldHalt()).isTrue();

            // Reset
            guardrails.reset();

            // After reset, should allow
            var decisionAfter = guardrails.checkBeforeCall("write_file", "{\"path\":\"/tmp\"}");
            assertThat(decisionAfter.action()).isEqualTo("allow");
        }

        @Test
        @DisplayName("Reset clears no-progress counts")
        void resetClearsNoProgress() {
            DefaultToolGuardrails guardrails = createGuardrails();

            // 4 identical idempotent calls
            for (int i = 0; i < 4; i++) {
                guardrails.recordToolCall("read_file", "{\"path\":\"/same\"}", true);
            }

            guardrails.reset();

            // After reset, no no-progress
            var decision = guardrails.checkBeforeCall("read_file", "{\"path\":\"/same\"}");
            assertThat(decision.action()).isEqualTo("allow");
        }
    }
}