package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

    @Test
    void requiresApprovalForConfiguredTools() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("terminal", "process"));
        ApprovalGate gate = new ApprovalGate(properties, new ApprovalQueue());

        assertThat(gate.requiresApproval(new ToolCall("tc-terminal", "terminal", "{}"))).isTrue();
        assertThat(gate.requiresApproval(new ToolCall("tc-read_file", "read_file", "{}"))).isFalse();
    }

    @Test
    void requestApprovalReturnsAssistantMessage() {
        AgentProperties properties = new AgentProperties();
        ApprovalGate gate = new ApprovalGate(properties, new ApprovalQueue());

        assertThat(gate.requestApproval(new ToolCall("tc-terminal", "terminal", "{\"cmd\":\"ls\"}")).content())
            .contains("terminal").contains("cmd");
    }

    @Test
    void requestApprovalInQueueCreatesPendingEntry() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("terminal"));
        ApprovalQueue queue = new ApprovalQueue();
        ApprovalGate gate = new ApprovalGate(properties, queue);

        UUID sessionId = UUID.randomUUID();
        ToolCall call = new ToolCall("tc1", "terminal", "{\"cmd\":\"ls\"}");
        ApprovalQueue.PendingApproval pending = gate.requestApproval(sessionId, call);

        assertThat(pending).isNotNull();
        assertThat(pending.sessionId()).isEqualTo(sessionId);
        assertThat(pending.call()).isEqualTo(call);
        assertThat(pending.approved()).isFalse();
        assertThat(pending.denied()).isFalse();
        assertThat(queue.isPending(sessionId)).isTrue();
    }
}