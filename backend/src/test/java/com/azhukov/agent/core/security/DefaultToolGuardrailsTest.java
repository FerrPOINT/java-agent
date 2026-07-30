package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link DefaultToolGuardrails}.
 *
 * <p>Current implementation only has:
 * <ul>
 *   <li>{@code isToolAllowed(String)} — checks name is non-blank</li>
 *   <li>{@code requiresApproval(ToolCall)} — checks if tool is in approval list</li>
 * </ul>
 *
 * <p>It does NOT have:
 * <ul>
 *   <li>Tool loop detection (same tool called N times with same args)</li>
 *   <li>Failure detection (same tool failing N times)</li>
 *   <li>Idempotent no-progress detection (read_file called 3x on same path)</li>
 *   <li>A blocked tools list</li>
 *   <li>Any stateful tracking of tool call history</li>
 *   <li>Any interface method for recording calls or checking loops</li>
 * </ul>
 * Tests below verify current behavior and document gaps via test names.
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
    void anyNonBlankToolNameIsAllowed_noBlockedToolsList() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // GAP: there is no blocked tools list — ANY non-blank name is allowed
        // Even dangerous-looking tool names pass
        assertThat(guardrails.isToolAllowed("exec")).isTrue();
        assertThat(guardrails.isToolAllowed("eval")).isTrue();
        assertThat(guardrails.isToolAllowed("system")).isTrue();
        assertThat(guardrails.isToolAllowed("rm_rf")).isTrue();
        assertThat(guardrails.isToolAllowed("arbitrary_tool_name")).isTrue();
    }

    // ─── Loop detection tests (GAP: no loop detection exists) ───

    @Test
    void toolLoopDetection_sameToolSameArgs_currentlyNotDetected_alwaysAllowed() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Call same tool 5 times — isToolAllowed is stateless, always returns true
        // GAP: no loop detection mechanism exists in the interface or implementation
        for (int i = 0; i < 5; i++) {
            assertThat(guardrails.isToolAllowed("read_file"))
                    .as("isToolAllowed is stateless — call #" + (i + 1) + " still allowed")
                    .isTrue();
        }
    }

    @Test
    void toolLoopDetection_sameToolSameArgs_currentlyNotDetected_noStateTracking() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Even 20 identical calls don't trigger any detection
        for (int i = 0; i < 20; i++) {
            assertThat(guardrails.isToolAllowed("write_file")).isTrue();
        }
    }

    @Test
    void toolLoopDetection_repeatedFailures_currentlyNotDetected() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // GAP: no method to report failures or track failure count
        // The interface only has isToolAllowed(String) and requiresApproval(ToolCall)
        // There is no recordFailure(String toolName) or similar method
        for (int i = 0; i < 3; i++) {
            // Simulate a failed tool call — but there's no way to report it
            assertThat(guardrails.isToolAllowed("failing_tool"))
                    .as("No failure tracking — tool still allowed after hypothetical failures")
                    .isTrue();
        }
    }

    @Test
    void toolLoopDetection_idempotentNoProgress_currentlyNotDetected() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // GAP: no method to check if same tool+args have been called before
        // read_file called 3 times on same path would be a no-progress loop
        // But isToolAllowed only checks the name, not arguments or history
        for (int i = 0; i < 3; i++) {
            assertThat(guardrails.isToolAllowed("read_file"))
                    .as("No idempotent no-progress detection")
                    .isTrue();
        }
    }

    @Test
    void toolLoopDetection_interfaceHasNoMethodForHistoryTracking() {
        // GAP: The ToolGuardrails interface has no method for:
        // - Recording tool calls
        // - Checking if a loop has been detected
        // - Getting call history
        // - Reporting failures
        // Only isToolAllowed(String) and requiresApproval(ToolCall) exist
        ToolGuardrails guardrails = new DefaultToolGuardrails(new AgentProperties());

        // Verify only the two existing methods are available
        assertThat(guardrails.isToolAllowed("any_tool")).isTrue();
        assertThat(guardrails.requiresApproval(null)).isFalse();
        // No other methods available on the interface
    }

    // ─── Approval tests ───

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
        // Default is empty ArrayList, not null — but test the path
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        assertThat(guardrails.requiresApproval(new ToolCall("1", "any_tool", "{}"))).isFalse();
    }

    @Test
    void requiresApproval_caseSensitive_matchMustBeExact() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Case-sensitive — "Write_File" does NOT match "write_file"
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

    // ─── Statelessness ───

    @Test
    void isToolAllowed_isStateless_noMemoryBetweenCalls() {
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Calling isToolAllowed many times with different tools — no state changes
        for (int i = 0; i < 10; i++) {
            assertThat(guardrails.isToolAllowed("tool_" + i)).isTrue();
        }
        // Going back to first tool — still allowed (no state tracking)
        assertThat(guardrails.isToolAllowed("tool_0")).isTrue();
    }

    @Test
    void requiresApproval_isStateless_noMemoryBetweenCalls() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Call requiresApproval many times — always returns same result
        for (int i = 0; i < 10; i++) {
            ToolCall call = new ToolCall(String.valueOf(i), "write_file", "{}");
            assertThat(guardrails.requiresApproval(call)).isTrue();
        }
    }

    // ─── ToolCall with different arguments (GAP: args not checked) ───

    @Test
    void requiresApproval_doesNotCheckArguments_onlyChecksName() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("write_file"));
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Different arguments — same result because only name is checked
        assertThat(guardrails.requiresApproval(new ToolCall("1", "write_file", "{\"path\":\"/etc/passwd\"}"))).isTrue();
        assertThat(guardrails.requiresApproval(new ToolCall("2", "write_file", "{\"path\":\"/tmp/safe.txt\"}"))).isTrue();
        // GAP: no argument-based approval (e.g., write_file to /tmp is fine, write_file to /etc needs approval)
    }

    @Test
    void isToolAllowed_doesNotConsiderArguments() {
        // isToolAllowed only takes a String (tool name), not arguments
        // GAP: no way to block specific argument patterns
        AgentProperties properties = new AgentProperties();
        DefaultToolGuardrails guardrails = new DefaultToolGuardrails(properties);

        // Dangerous tool name is allowed regardless
        assertThat(guardrails.isToolAllowed("dangerous_tool")).isTrue();
    }
}