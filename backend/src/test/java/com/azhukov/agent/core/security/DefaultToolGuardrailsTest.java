package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link DefaultToolGuardrails}.
 *
 * <p>Implementation now includes:
 * <ul>
 *   <li>{@code isToolAllowed(String)} — checks name is non-blank and not in blocked list</li>
 *   <li>{@code requiresApproval(ToolCall)} — checks if tool is in approval list</li>
 *   <li>{@code recordToolCall(String, String, boolean)} — records calls for loop detection</li>
 *   <li>{@code isHalted()} — returns true when loop detection halts further calls</li>
 *   <li>{@code reset()} — clears state between turns</li>
 *   <li>Loop detection: 5+ identical calls halt, 3+ consecutive failures halt, 10+ total warns</li>
 *   <li>Configurable blocked tools list via getBlockedTools()/setBlockedTools()</li>
 * </ul>
 */
class DefaultToolGuardrailsTest {

    // ─── Existing tests (preserved) ───

    @Test
    void nonBlankToolNameIsAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.isToolAllowed("read_file")).isTrue();
        assertThat(guardrails.isToolAllowed("")).isFalse();
    }

    @Test
    void requiresApprovalForConfiguredTools() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file", "terminal"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "read_file", "{}"))).isFalse();
    }

    @Test
    void noApprovalWhenApprovalsDisabled() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(false);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isFalse();
    }

    @Test
    void nullToolCallDoesNotRequireApproval() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.requiresApproval(null)).isFalse();
    }

    // ─── Tool name validation ───

    @Test
    void nullToolName_isNotAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.isToolAllowed(null)).isFalse();
    }

    @Test
    void blankToolName_isNotAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.isToolAllowed("")).isFalse();
    }

    @Test
    void whitespaceOnlyToolName_isNotAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        assertThat(guardrails.isToolAllowed("   ")).isFalse();
        assertThat(guardrails.isToolAllowed("\t\n")).isFalse();
    }

    @Test
    void anyNonBlankToolNameIsAllowed_whenNoBlockedTools() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // With no blocked tools configured, any non-blank name is allowed
        assertThat(guardrails.isToolAllowed("exec")).isTrue();
        assertThat(guardrails.isToolAllowed("eval")).isTrue();
        assertThat(guardrails.isToolAllowed("system")).isTrue();
        assertThat(guardrails.isToolAllowed("arbitrary_tool_name")).isTrue();
    }

    // ─── Blocked tools list tests (NEW) ───

    @Test
    void blockedTools_toolInBlockedList_isNotAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        guardrails.setBlockedTools(Set.of("exec", "eval", "system"));

        assertThat(guardrails.isToolAllowed("exec")).isFalse();
        assertThat(guardrails.isToolAllowed("eval")).isFalse();
        assertThat(guardrails.isToolAllowed("system")).isFalse();
    }

    @Test
    void blockedTools_toolNotInBlockedList_isAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        guardrails.setBlockedTools(Set.of("exec", "eval"));

        assertThat(guardrails.isToolAllowed("read_file")).isTrue();
        assertThat(guardrails.isToolAllowed("write_file")).isTrue();
    }

    @Test
    void blockedTools_emptySet_allAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        guardrails.setBlockedTools(Set.of());

        assertThat(guardrails.isToolAllowed("exec")).isTrue();
        assertThat(guardrails.isToolAllowed("any_tool")).isTrue();
    }

    @Test
    void blockedTools_nullSet_allAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        guardrails.setBlockedTools(null);

        assertThat(guardrails.isToolAllowed("exec")).isTrue();
    }

    @Test
    void getBlockedTools_returnsEmptyByDefault() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.getBlockedTools()).isEmpty();
    }

    @Test
    void getBlockedTools_returnsConfiguredSet() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        Set<String> blocked = Set.of("exec", "eval");
        guardrails.setBlockedTools(blocked);

        assertThat(guardrails.getBlockedTools()).containsExactlyInAnyOrder("exec", "eval");
    }

    // ─── Loop detection tests (FIXED: loop detection now works) ───

    @Test
    void loopDetection_sameToolSameArgs_5calls_haltsWithNoProgress() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Call same tool with same args 5 times → should halt
        for (int i = 0; i < 4; i++) {
            guardrails.recordToolCall("read_file", "{\"path\":\"/tmp/test\"}", true);
            assertThat(guardrails.isHalted())
                    .as("Not yet halted after " + (i + 1) + " identical calls")
                    .isFalse();
        }
        // 5th identical call triggers halt
        guardrails.recordToolCall("read_file", "{\"path\":\"/tmp/test\"}", true);
        assertThat(guardrails.isHalted()).isTrue();
    }

    @Test
    void loopDetection_sameToolSameArgs_20calls_haltsAndBlocksTool() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Even 20 identical calls — should halt after 5
        for (int i = 0; i < 20; i++) {
            guardrails.recordToolCall("write_file", "{}", true);
        }
        assertThat(guardrails.isHalted()).isTrue();
        // Once halted, isToolAllowed returns false
        assertThat(guardrails.isToolAllowed("write_file")).isFalse();
    }

    @Test
    void loopDetection_repeatedFailures_3consecutive_haltsWithFailureLoop() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // 3 consecutive failures → halt
        guardrails.recordToolCall("failing_tool", "{}", false);
        assertThat(guardrails.isHalted()).isFalse();

        guardrails.recordToolCall("failing_tool", "{}", false);
        assertThat(guardrails.isHalted()).isFalse();

        guardrails.recordToolCall("failing_tool", "{}", false);
        assertThat(guardrails.isHalted()).isTrue();
    }

    @Test
    void loopDetection_idempotentNoProgress_5sameCalls_halts() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // read_file called 5 times on same path → no-progress loop detected
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall("read_file", "{\"path\":\"/same/path\"}", true);
        }
        assertThat(guardrails.isHalted()).isTrue();
    }

    @Test
    void loopDetection_interfaceHasRecordAndHaltAndReset() {
        // Verify the interface now has recordToolCall, isHalted, and reset
        ToolGuardrails guardrails = new DefaultToolGuardrails(new AgentProperties());

        assertThat(guardrails.isToolAllowed("any_tool")).isTrue();
        assertThat(guardrails.requiresApproval(null)).isFalse();
        assertThat(guardrails.isHalted()).isFalse();

        // Record some calls
        guardrails.recordToolCall("test_tool", "{}", true);
        guardrails.reset();
        assertThat(guardrails.isHalted()).isFalse();
    }

    // ─── Reset tests ───

    @Test
    void reset_clearsHaltedState() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Trigger halt via 5 identical calls
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall("read_file", "{}", true);
        }
        assertThat(guardrails.isHalted()).isTrue();

        // Reset clears state
        guardrails.reset();
        assertThat(guardrails.isHalted()).isFalse();
        assertThat(guardrails.isToolAllowed("read_file")).isTrue();
    }

    @Test
    void reset_clearsFailureCount() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // 3 consecutive failures → halt
        for (int i = 0; i < 3; i++) {
            guardrails.recordToolCall("failing_tool", "{}", false);
        }
        assertThat(guardrails.isHalted()).isTrue();

        // Reset clears state
        guardrails.reset();
        assertThat(guardrails.isHalted()).isFalse();

        // After reset, 2 failures should NOT halt (need 3 again)
        guardrails.recordToolCall("failing_tool", "{}", false);
        guardrails.recordToolCall("failing_tool", "{}", false);
        assertThat(guardrails.isHalted()).isFalse();
    }

    @Test
    void reset_clearsIdenticalArgsCount() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // 4 identical calls — not yet halted
        for (int i = 0; i < 4; i++) {
            guardrails.recordToolCall("read_file", "{\"path\":\"/test\"}", true);
        }
        assertThat(guardrails.isHalted()).isFalse();

        // Reset
        guardrails.reset();

        // After reset, 4 more identical calls should NOT halt (need 5 again)
        for (int i = 0; i < 4; i++) {
            guardrails.recordToolCall("read_file", "{\"path\":\"/test\"}", true);
        }
        assertThat(guardrails.isHalted()).isFalse();
    }

    // ─── Different args don't trigger identical-args loop ───

    @Test
    void loopDetection_differentArgs_doesNotTriggerIdenticalArgsHalt() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Same tool but different args each time — should not trigger identical-args halt
        for (int i = 0; i < 10; i++) {
            guardrails.recordToolCall("read_file", "{\"path\":\"/file" + i + "\"}", true);
        }
        assertThat(guardrails.isHalted()).isFalse();
    }

    // ─── Success resets consecutive failure count ───

    @Test
    void loopDetection_successResetsConsecutiveFailures() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // 2 failures with same args
        guardrails.recordToolCall("tool", "{\"v\":1}", false);
        guardrails.recordToolCall("tool", "{\"v\":1}", false);
        assertThat(guardrails.isHalted()).isFalse();

        // Success with different args resets consecutive failure count
        guardrails.recordToolCall("tool", "{\"v\":2}", true);
        assertThat(guardrails.isHalted()).isFalse();

        // 2 more failures with different args — should not halt (need 3 consecutive)
        guardrails.recordToolCall("tool", "{\"v\":3}", false);
        guardrails.recordToolCall("tool", "{\"v\":4}", false);
        assertThat(guardrails.isHalted()).isFalse();
    }

    // ─── Halted state blocks all tools ───

    @Test
    void halted_blocksAllTools() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Trigger halt
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall("read_file", "{}", true);
        }
        assertThat(guardrails.isHalted()).isTrue();

        // All tools are blocked when halted
        assertThat(guardrails.isToolAllowed("read_file")).isFalse();
        assertThat(guardrails.isToolAllowed("write_file")).isFalse();
        assertThat(guardrails.isToolAllowed("any_tool")).isFalse();
    }

    // ─── Null/blank args handling ───

    @Test
    void recordToolCall_nullArgs_treatedAsEmptyString() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // null args should be treated as empty string, not cause NPE
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall("tool", null, true);
        }
        assertThat(guardrails.isHalted()).isTrue();
    }

    @Test
    void recordToolCall_nullToolName_ignored() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // null tool name should be ignored, not cause NPE or tracking
        guardrails.recordToolCall(null, "{}", true);
        guardrails.recordToolCall(null, "{}", true);
        guardrails.recordToolCall(null, "{}", true);
        guardrails.recordToolCall(null, "{}", true);
        guardrails.recordToolCall(null, "{}", true);
        assertThat(guardrails.isHalted()).isFalse();
    }

    @Test
    void recordToolCall_blankToolName_ignored() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        guardrails.recordToolCall("", "{}", true);
        guardrails.recordToolCall("  ", "{}", true);
        assertThat(guardrails.isHalted()).isFalse();
    }

    // ─── Approval tests (preserved) ───

    @Test
    void requiresApproval_emptyApprovalList_alwaysFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isFalse();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "terminal", "{}"))).isFalse();
        assertThat(guardrails.requiresApproval(new ToolCall("3", "read_file", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_toolInList_requiresApproval() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isTrue();
    }

    @Test
    void requiresApproval_toolNotInList_noApproval() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "read_file", "{}"))).isFalse();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "delete_file", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_approvalsEnabledButNullList_noApproval() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "any_tool", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_caseSensitive_matchMustBeExact() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "Write_File", "{}"))).isFalse();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "WRITE_FILE", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_multipleToolsInList() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(
                List.of("write_file", "terminal", "delete_file", "exec"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "terminal", "{}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("3", "delete_file", "{}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("4", "exec", "{}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("5", "read_file", "{}"))).isFalse();
    }

    // ─── Approval disabled edge cases ───

    @Test
    void requiresApproval_approvalsDisabledEvenWithToolsListed_noApproval() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(false);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file", "terminal"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{}"))).isFalse();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "terminal", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_nullCallWithApprovalsEnabled_returnsFalse() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(null)).isFalse();
    }

    // ─── Statefulness after recordToolCall ───

    @Test
    void isToolAllowed_isStateful_afterHalt_blocksAllTools() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Before any recording, all tools allowed
        for (int i = 0; i < 10; i++) {
            assertThat(guardrails.isToolAllowed("tool_" + i)).isTrue();
        }

        // Trigger halt via 5 identical calls
        for (int i = 0; i < 5; i++) {
            guardrails.recordToolCall("tool_0", "{}", true);
        }

        // After halt, all tools blocked
        assertThat(guardrails.isToolAllowed("tool_0")).isFalse();
        assertThat(guardrails.isToolAllowed("tool_1")).isFalse();
    }

    @Test
    void requiresApproval_isStateless_alwaysReturnsSameResult() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // requiresApproval is stateless — always returns same result regardless of call history
        for (int i = 0; i < 10; i++) {
            ToolCall call = new ToolCall(String.valueOf(i), "write_file", "{}");
            assertThat(guardrails.requiresApproval(call)).isTrue();
        }
    }

    // ─── ToolCall with different arguments ───

    @Test
    void requiresApproval_doesNotCheckArguments_onlyChecksName() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{\"path\":\"/etc/passwd\"}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "write_file", "{\"path\":\"/tmp/safe.txt\"}"))).isTrue();
    }

    @Test
    void isToolAllowed_considersBlockedToolsList() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);
        guardrails.setBlockedTools(Set.of("dangerous_tool"));

        assertThat(guardrails.isToolAllowed("dangerous_tool")).isFalse();
        assertThat(guardrails.isToolAllowed("safe_tool")).isTrue();
    }
}