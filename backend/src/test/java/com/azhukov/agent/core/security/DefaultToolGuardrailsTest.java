package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolGuardrailsTest {

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
}
