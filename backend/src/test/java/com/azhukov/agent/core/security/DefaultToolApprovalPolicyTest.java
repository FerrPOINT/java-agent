package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultToolApprovalPolicyTest {
    private AgentProperties properties() {
        var properties = new AgentProperties();
        properties.getSecurity().setApprovalsEnabled(true);
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("terminal"));
        return properties;
    }

    @Test
    void localPolicyAndExplicitDisabledModeRemainDistinct() {
        var properties = properties();
        var policy = new DefaultToolApprovalPolicy(properties, new ApprovalQueue());
        var terminal = new ToolCall("1", "terminal", "{}");
        assertThat(policy.requiresApproval(terminal, null)).isTrue();
        assertThat(policy.requiresApproval(new ToolCall("2", "read_file", "{}"), null)).isFalse();
        properties.getSecurity().setApprovalsEnabled(false);
        assertThat(policy.requiresApproval(terminal, null)).isFalse();
    }

    @Test
    void malformedOrUnregisteredMcpCannotLoseItsAdmissionRequirement() {
        var queue = new ApprovalQueue();
        var policy = new DefaultToolApprovalPolicy(properties(), queue);
        var call = new ToolCall("1", "mcp_tool", "{\"server_name\":\"srv\",\"tool_name\":\"tool\"}");
        assertThatThrownBy(() -> policy.mcpAdmission(call, null)).isInstanceOf(IllegalStateException.class);
        var trust = mock(McpToolTrustService.class);
        policy.setMcpToolTrustService(trust);
        assertThatThrownBy(() -> policy.mcpAdmission(new ToolCall("2", "mcp_tool", "{}"), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.mcpAdmission(call, null)).isInstanceOf(IllegalArgumentException.class);
        var admission = mock(McpToolTrustService.ToolTrustMetadata.class);
        when(trust.metadata("srv", "tool")).thenReturn(admission);
        when(admission.requiresApproval()).thenReturn(true);
        assertThat(policy.mcpAdmission(call, null)).isSameAs(admission);
        assertThat(policy.requiresApproval(call, admission)).isTrue();
    }

    @Test
    void pendingApprovalBelongsToTheRealSessionQueue() {
        var queue = new ApprovalQueue();
        var policy = new DefaultToolApprovalPolicy(properties(), queue);
        UUID session = UUID.randomUUID(), neighbor = UUID.randomUUID();
        policy.requestApproval(session, new ToolCall("1", "terminal", "{}"));
        assertThat(queue.isPending(session)).isTrue();
        assertThat(queue.isPending(neighbor)).isFalse();
        assertThatThrownBy(() -> policy.requestApproval(null, new ToolCall("2", "terminal", "{}")))
            .isInstanceOf(NullPointerException.class);
    }
}
