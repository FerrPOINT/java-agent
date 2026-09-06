package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage + regression for TurnFinalizer branches: exit-reason inference,
 * interrupted tool-sequence closing, empty/partial response explanations.
 */
class TurnFinalizerBranchTest {

    private final TurnFinalizer finalizer = new TurnFinalizer(new PromptCacheTracker(new AgentProperties()));

    private static final UUID SID = UUID.randomUUID();

    // ── inferExitReason ─────────────────────────────────────────────

    @Test
    void inferFailureReasonsFromLastMessage() {
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("budget exhausted", 0)), false))
            .isEqualTo(TurnExitReason.BUDGET_EXHAUSTED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("halted by guardrails", 0)), false))
            .isEqualTo(TurnExitReason.GUARDRAIL_HALTED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("turn cancelled by user", 0)), false))
            .isEqualTo(TurnExitReason.INTERRUPTED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("Model call failed: 500", 0)), false))
            .isEqualTo(TurnExitReason.MODEL_CALL_FAILED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("Reached max turns", 0)), false))
            .isEqualTo(TurnExitReason.MAX_TURNS_REACHED);
    }

    @Test
    void inferPendingToolResultAndUnknown() {
        assertThat(TurnFinalizer.inferExitReason(null, false)).isEqualTo(TurnExitReason.UNKNOWN);
        assertThat(TurnFinalizer.inferExitReason(List.of(), false)).isEqualTo(TurnExitReason.UNKNOWN);
        // last is TOOL with no recognized text → PENDING_TOOL_RESULT
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.toolResult("t1", "ok", 0)), false))
            .isEqualTo(TurnExitReason.PENDING_TOOL_RESULT);
        // last is assistant with unrecognized text → UNKNOWN
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("mystery", 0)), false))
            .isEqualTo(TurnExitReason.UNKNOWN);
    }

    @Test
    void inferSuccessReasons() {
        assertThat(TurnFinalizer.inferExitReason(null, true)).isEqualTo(TurnExitReason.COMPLETED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("done.", 0)), true))
            .isEqualTo(TurnExitReason.COMPLETED);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant("   ", 0)), true))
            .isEqualTo(TurnExitReason.EMPTY_RESPONSE);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.assistant((String) null, 0)), true))
            .isEqualTo(TurnExitReason.EMPTY_RESPONSE);
        assertThat(TurnFinalizer.inferExitReason(List.of(Message.user("x"), Message.toolResult("t1", "late", 0)), true))
            .isEqualTo(TurnExitReason.PENDING_TOOL_RESULT);
    }

    // ── finalize: interrupted tool sequence closing ────────────────

    @Test
    void failedTurnClosesDanglingToolSequenceWithSyntheticAssistant() {
        List<Message> messages = new ArrayList<>(List.of(
            Message.user("run"),
            Message.assistantToolCalls(List.of(new ToolCall("c1", "terminal", "{}")), 0),
            Message.toolResult("c1", "partial output", 0)));

        var result = finalizer.finalize(SID, messages, false, TurnExitReason.INTERRUPTED);

        // Synthetic assistant message must be appended after the dangling TOOL msg
        assertThat(messages.get(messages.size() - 1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(messages.get(messages.size() - 1).content())
            .isEqualTo("[Turn ended: interrupted by user]");
        // The synthetic closing message is long enough not to be a partial fragment,
        // and no file mutations happened → no footer, no extra explanation.
        assertThat(result).isNull();
    }

    @Test
    void failedTurnWithPartialAssistantFragmentAppendsExplanation() {
        // Short fragment without terminal punctuation → explanation appended
        List<Message> messages = new ArrayList<>(List.of(
            Message.user("run"),
            Message.assistant("partial frag", 0)));

        var result = finalizer.finalize(SID, messages, false, TurnExitReason.INTERRUPTED);
        assertThat(result).isNotNull();
        assertThat(result.completionExplanation()).isEqualTo("[Turn ended: interrupted by user]");
    }

    @Test
    void failedWriteMutationFooterReportsUnsupersededFailures() {
        List<Message> messages = new ArrayList<>(List.of(
            Message.user("write"),
            Message.assistantToolCalls(List.of(
                new ToolCall("c1", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"x\"}")), 0),
            Message.toolResult("c1", "Error: disk full", 0)));

        var result = finalizer.finalize(SID, messages, false, TurnExitReason.MODEL_CALL_FAILED);
        assertThat(result).isNotNull();
        assertThat(result.fileMutationFooter()).contains("/tmp/a.txt");
    }

    @Test
    void failedTurnWithEmptyMessagesReturnsExplanationOnlyForAbnormalReason() {
        var result = finalizer.finalize(SID, List.of(), false, TurnExitReason.MODEL_CALL_FAILED);
        assertThat(result).isNotNull();
        assertThat(result.completionExplanation()).isNotBlank();
        assertThat(result.fileMutationFooter()).isNull();

        // Non-abnormal reason with no messages → nothing
        assertThat(finalizer.finalize(SID, List.of(), true, TurnExitReason.COMPLETED)).isNull();
        assertThat(finalizer.finalize(SID, null, true, null)).isNull();
    }

    @Test
    void successTurnKeepsMessagesUntouchedWhenContentIsNormal() {
        List<Message> messages = new ArrayList<>(List.of(
            Message.user("hi"),
            Message.assistant("Here is a complete answer with terminal punctuation.", 0)));

        var result = finalizer.finalize(SID, messages, true, TurnExitReason.COMPLETED);
        // No file mutations, normal content → no footer; completed is not abnormal → no explanation
        assertThat(result).isNull();
        assertThat(messages).hasSize(2);
    }
}
