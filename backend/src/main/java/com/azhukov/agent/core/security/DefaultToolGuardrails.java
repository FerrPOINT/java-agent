package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultToolGuardrails implements ToolGuardrails {

    private final AgentProperties properties;

    public DefaultToolGuardrails(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isToolAllowed(String toolName) {
        return toolName != null && !toolName.isBlank();
    }

    @Override
    public boolean requiresApproval(ToolCall call) {
        if (!properties.getSecurity().isApprovalsEnabled() || call == null) {
            return false;
        }
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }
}
