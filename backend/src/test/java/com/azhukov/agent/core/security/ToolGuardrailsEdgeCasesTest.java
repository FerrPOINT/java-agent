package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional focused tests for DefaultToolGuardrails covering edge cases,
 * error handling, and GuardrailDecision semantics.
 */
@ExtendWith(MockitoExtension.class)
class ToolGuardrailsEdgeCasesTest {

    @Mock private ApprovalQueue approvalQueue;

    private AgentProperties properties;
    private DefaultToolGuardrails guardrails;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        guardrails = new DefaultToolGuardrails(properties, approvalQueue);
        sessionId = UUID.randomUUID();
    }

    // ─── requestApproval with null/edge inputs ───

    @Test
    void requestApproval_normalCall_delegatesToQueue() {
        ToolCall call = new ToolCall("id", "terminal", "{}");
        ApprovalQueue.PendingApproval pending = new ApprovalQueue.PendingApproval(
            UUID.randomUUID(), sessionId, call, "Approval required for tool: terminal",
            java.time.Instant.now(), false, false, null,
            java.time.Instant.now().plusSeconds(300));
        when(approvalQueue.request(any(), any(), anyString())).thenReturn(pending);
        ApprovalQueue.PendingApproval result = guardrails.requestApproval(sessionId, call);
        assertThat(result).isSameAs(pending);
        verify(approvalQueue).request(sessionId, call, "Approval required for tool: terminal");
    }

    // ─── checkBeforeCall: no-progress on idempotent tools ───

    @Test
    void checkBeforeCall_idempotentNoProgress_returnsWarningThenBlock() {
        // Record 2 successful identical calls to trigger no-progress warning
        guardrails.recordToolCall(sessionId, "read_file", "same-args", true);
        guardrails.recordToolCall(sessionId, "read_file", "same-args", true);
        guardrails.recordToolCall(sessionId, "read_file", "same-args", true);
        // 3 identical successful calls → noProgressCount should be 2 (starts at 2nd identical call)
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "read_file", "same-args");
        // Should at least warn about no-progress
        assertThat(decision.action()).isIn("warn", "halt", "block");
    }

    @Test
    void checkBeforeCall_idempotentNoProgressBlockAfter5_returnsHalt() {
        // Record 5 successful identical calls to trigger no-progress block
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall(sessionId, "read_file", "same-args", true);
        }
        // Now noProgressCounts should be >= 5 → block
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "read_file", "same-args");
        assertThat(decision.shouldHalt()).isTrue();
        assertThat(decision.code()).isEqualTo("idempotent_no_progress_block");
    }

    // ─── checkBeforeCall: warning threshold for exact failures ───

    @Test
    void checkBeforeCall_exactFailureWarningAfter2_returnsWarning() {
        // Record 2 exact failures (same tool + same args)
        guardrails.recordToolCall(sessionId, "terminal", "ls -la", false);
        guardrails.recordToolCall(sessionId, "terminal", "ls -la", false);
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "ls -la");
        // 2 exact failures → warning (not block yet, block is at 5)
        assertThat(decision.action()).isEqualTo("warn");
        assertThat(decision.code()).isEqualTo("repeated_exact_failure_warning");
    }

    @Test
    void checkBeforeCall_exactFailureHaltWhenAlreadyHalted() {
        // First trigger halt via 3 consecutive failures with different args
        guardrails.recordToolCall(sessionId, "terminal", "cmd1", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd2", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd3", false);
        // Now halted is true
        assertThat(guardrails.isHalted(sessionId)).isTrue();
        // Also record 2 exact failures with same args
        guardrails.recordToolCall(sessionId, "terminal", "cmd4", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd4", false);
        // Now check — should escalate warning to halt
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "cmd4");
        assertThat(decision.shouldHalt()).isTrue();
    }

    // ─── checkBeforeCall: same tool failure warning ───

    @Test
    void checkBeforeCall_sameToolFailureWarningAfter3_returnsWarning() {
        // Record 3 failures with different args for the same tool
        guardrails.recordToolCall(sessionId, "terminal", "cmd-a", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd-b", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd-c", false);
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "cmd-d");
        // 3 same-tool failures → warning (block threshold is 8)
        assertThat(decision.action()).isIn("warn", "halt");
    }

    @Test
    void checkBeforeCall_sameToolFailureHaltAfter8_returnsHalt() {
        // Record 8 failures with different args for the same tool
        for (int i = 0; i < 8; i++) {
            guardrails.recordToolCall(sessionId, "terminal", "cmd-" + i, false);
        }
        // Halted should be true via sameToolFailureCounts threshold
        assertThat(guardrails.isHalted(sessionId)).isTrue();
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "new-cmd");
        assertThat(decision.shouldHalt()).isTrue();
    }

    // ─── checkBeforeCall: different args resets identical count ───

    @Test
    void checkBeforeCall_differentArgsResetsIdenticalCount_noHalt() {
        // Record 4 identical calls (below threshold of 5)
        for (int i = 0; i < 4; i++) {
            guardrails.recordToolCall(sessionId, "read_file", "same-args", true);
        }
        // Change args — should reset identical count
        guardrails.recordToolCall(sessionId, "read_file", "different-args", true);
        // Check — should be allowed
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "read_file", "more-args");
        assertThat(decision.allowsExecution()).isTrue();
    }

    // ─── GuardrailDecision record semantics ───

    @Test
    void guardrailDecision_allow_hasEmptyMessage() {
        DefaultToolGuardrails.GuardrailDecision d = DefaultToolGuardrails.GuardrailDecision.allow("tool");
        assertThat(d.message()).isEmpty();
        assertThat(d.toolName()).isEqualTo("tool");
    }

    @Test
    void guardrailDecision_warn_hasMessageAndCode() {
        DefaultToolGuardrails.GuardrailDecision d = DefaultToolGuardrails.GuardrailDecision.warn("tool", "warn-code", "warning msg");
        assertThat(d.message()).isEqualTo("warning msg");
        assertThat(d.code()).isEqualTo("warn-code");
        assertThat(d.toolName()).isEqualTo("tool");
    }

    @Test
    void guardrailDecision_block_hasMessageAndCode() {
        DefaultToolGuardrails.GuardrailDecision d = DefaultToolGuardrails.GuardrailDecision.block("tool", "block-code", "blocked msg");
        assertThat(d.message()).isEqualTo("blocked msg");
        assertThat(d.code()).isEqualTo("block-code");
    }

    @Test
    void guardrailDecision_halt_hasMessageAndCode() {
        DefaultToolGuardrails.GuardrailDecision d = DefaultToolGuardrails.GuardrailDecision.halt("tool", "halt-code", "halted msg");
        assertThat(d.message()).isEqualTo("halted msg");
        assertThat(d.code()).isEqualTo("halt-code");
    }

    // ─── ToolGuardrails interface default methods ───

    @Test
    void toolGuardrails_defaultRequestApproval_returnsNull() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.requestApproval(UUID.randomUUID(), new ToolCall("id", "tool", "{}"))).isNull();
    }

    @Test
    void toolGuardrails_defaultRecordToolCall_isNoOp() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        tg.recordToolCall("tool", "args", true); // should not throw
    }

    @Test
    void toolGuardrails_defaultIsHalted_returnsFalse() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.isHalted()).isFalse();
    }

    @Test
    void toolGuardrails_defaultIsHaltedWithSession_delegatesToIsHalted() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.isHalted(UUID.randomUUID())).isFalse();
    }

    @Test
    void toolGuardrails_defaultReset_isNoOp() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        tg.reset(); // should not throw
    }

    @Test
    void toolGuardrails_defaultResetWithSession_delegatesToReset() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        tg.reset(UUID.randomUUID()); // should not throw
    }

    @Test
    void toolGuardrails_defaultGetBlockedTools_returnsEmpty() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.getBlockedTools()).isEmpty();
    }

    @Test
    void toolGuardrails_defaultSetBlockedTools_isNoOp() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        tg.setBlockedTools(Set.of("tool1", "tool2")); // should not throw
        assertThat(tg.getBlockedTools()).isEmpty(); // still empty (no-op)
    }

    @Test
    void toolGuardrails_defaultIsMutating_returnsFalse() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.isMutating("any_tool")).isFalse();
    }

    @Test
    void toolGuardrails_defaultIsIdempotent_returnsFalse() {
        ToolGuardrails tg = new ToolGuardrails() {
            @Override public boolean isToolAllowed(String toolName) { return true; }
            @Override public boolean requiresApproval(ToolCall call) { return false; }
        };
        assertThat(tg.isIdempotent("any_tool")).isFalse();
    }

    // ─── legacy checkBeforeCall overload (no sessionId) ───

    @Test
    void checkBeforeCall_legacyOverload_noSessionContext_usesGlobalSession() {
        // Without setting session context, should use GLOBAL_SESSION_ID
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall("read_file", "args");
        assertThat(decision.allowsExecution()).isTrue();
    }

    // ─── getToolCallCount / getToolFailureCount for unknown session ───

    @Test
    void getToolCallCount_unknownSession_returnsZero() {
        assertThat(guardrails.getToolCallCount(UUID.randomUUID(), "read_file")).isEqualTo(0);
    }

    @Test
    void getToolFailureCount_unknownSession_returnsZero() {
        assertThat(guardrails.getToolFailureCount(UUID.randomUUID(), "terminal")).isEqualTo(0);
    }

    @Test
    void isHalted_unknownSession_returnsFalse() {
        assertThat(guardrails.isHalted(UUID.randomUUID())).isFalse();
    }

    @Test
    void isWarned_unknownSession_returnsFalse() {
        assertThat(guardrails.isWarned(UUID.randomUUID())).isFalse();
    }

    // ─── success clears exact failure tracking ───

    @Test
    void recordToolCall_successClearsExactFailureTracking() {
        // Record 2 exact failures
        guardrails.recordToolCall(sessionId, "terminal", "ls", false);
        guardrails.recordToolCall(sessionId, "terminal", "ls", false);
        // Now succeed with the same args
        guardrails.recordToolCall(sessionId, "terminal", "ls", true);
        // Check — should be allowed (exact failures cleared)
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "ls");
        assertThat(decision.allowsExecution()).isTrue();
    }

    @Test
    void recordToolCall_successClearsSameToolFailureTracking() {
        // Record 3 same-tool failures (different args)
        guardrails.recordToolCall(sessionId, "terminal", "cmd1", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd2", false);
        guardrails.recordToolCall(sessionId, "terminal", "cmd3", false);
        // Succeed with different args
        guardrails.recordToolCall(sessionId, "terminal", "cmd4", true);
        // Check — should be allowed (same-tool failures cleared)
        DefaultToolGuardrails.GuardrailDecision decision = guardrails.checkBeforeCall(sessionId, "terminal", "cmd5");
        assertThat(decision.allowsExecution()).isTrue();
    }
}