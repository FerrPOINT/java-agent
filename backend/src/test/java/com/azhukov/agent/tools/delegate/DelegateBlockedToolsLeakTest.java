package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Hermes-parity regression: leaf children must not retain blocked TOOLS via
 * mixed composite toolsets (audit finding: hermes-cli expanded to
 * delegate_task/clarify/memory/cronjob survived the old toolset-name strip).
 */
class DelegateBlockedToolsLeakTest {

    private DelegateTaskTool newTool() {
        AgentProperties p = new AgentProperties();
        ObjectProvider<com.azhukov.agent.core.agent.AgentRuntime> runtimeProvider = mock(ObjectProvider.class);
        ObjectProvider<com.azhukov.agent.core.tool.ToolRegistry> registryProvider = mock(ObjectProvider.class);
        return new DelegateTaskTool(p, runtimeProvider, registryProvider, 5);
    }

    @Test
    void blockedToolNamesForLeafDenyContractSet() {
        assertThat(DelegateTaskTool.blockedToolNamesForRole("leaf"))
            .containsExactlyInAnyOrder("delegate_task", "clarify", "memory", "send_message", "cronjob");
    }

    @Test
    void blockedToolNamesForOrchestratorRegainOnlyDelegateTask() {
        Set<String> denied = DelegateTaskTool.blockedToolNamesForRole("orchestrator");
        assertThat(denied).doesNotContain("delegate_task");
        assertThat(denied).contains("clarify", "memory", "send_message", "cronjob");
    }

    @Test
    void hermesCliCompositeIsStrippedForLeafChildren() {
        // hermes-cli is a mixed bundle: it contains blocked AND allowed tools.
        // The old toolset-name strip left the whole composite intact → leak.
        DelegateTaskTool tool = newTool();
        Set<String> parent = new LinkedHashSet<>(List.of("hermes-cli"));
        List<String> child = tool.resolveChildToolsets(null, parent, "leaf");

        // The composite itself survives stripBlockedToolsets (mixed)…
        assertThat(child).contains("hermes-cli");
        // …but the runtime deny-list (delegation_blocked_tools metadata) must
        // contain every blocked name, which DefaultAgentRuntime subtracts AFTER
        // composite expansion.
        Set<String> denied = DelegateTaskTool.blockedToolNamesForRole("leaf");
        assertThat(denied).contains("delegate_task", "clarify", "memory", "cronjob", "send_message");
    }

    @Test
    void legacyStripStillRemovesFullyBlockedToolsets() {
        DelegateTaskTool tool = newTool();
        Set<String> parent = new LinkedHashSet<>(List.of(
            "web", "file", "delegation", "memory", "gateway", "cronjob", "clarify"));
        List<String> child = tool.resolveChildToolsets(null, parent, "leaf");
        assertThat(child).contains("web", "file");
        assertThat(child).doesNotContain("delegation", "memory", "gateway", "cronjob", "clarify");
    }

    @Test
    void orchestratorReaddsDelegationToolset() {
        DelegateTaskTool tool = newTool();
        Set<String> parent = new LinkedHashSet<>(List.of("web", "delegation"));
        List<String> child = tool.resolveChildToolsets(null, parent, "orchestrator");
        assertThat(child).contains("web", "delegation");
    }
}
