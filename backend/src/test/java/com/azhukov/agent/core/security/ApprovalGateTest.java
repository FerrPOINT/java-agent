package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalGateTest {

    @Test
    void requiresApprovalForConfiguredTools() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setAlwaysRequireApprovalTools(List.of("terminal", "process"));
        ApprovalGate gate = new ApprovalGate(properties);

        assertThat(gate.requiresApproval(new ToolCall("tc-terminal", "terminal", "{}"))).isTrue();
        assertThat(gate.requiresApproval(new ToolCall("tc-read_file", "read_file", "{}"))).isFalse();
    }

    @Test
    void requestApprovalReturnsAssistantMessage() {
        AgentProperties properties = new AgentProperties();
        ApprovalGate gate = new ApprovalGate(properties);

        assertThat(gate.requestApproval(new ToolCall("tc-terminal", "terminal", "{\"cmd\":\"ls\"}")).content())
            .contains("terminal").contains("cmd");
    }
}
