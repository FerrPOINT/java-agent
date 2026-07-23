package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApprovalGate {

    private final AgentProperties properties;

    public ApprovalGate(AgentProperties properties) {
        this.properties = properties;
    }

    public boolean requiresApproval(ToolCall call) {
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }

    public Message requestApproval(ToolCall call) {
        return Message.assistant("Requesting approval for tool call: " + call.name() + "(" + call.arguments() + ")", 0);
    }
}
