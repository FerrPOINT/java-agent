package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApprovalGate {

    private final AgentProperties properties;
    private final ApprovalQueue approvalQueue;


    public boolean requiresApproval(ToolCall call) {
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }

    public Message requestApproval(ToolCall call) {
        return Message.assistant("Requesting approval for tool call: " + call.name() + "(" + call.arguments() + ")", 0);
    }

    /**
     * Creates a pending approval request in the queue for the given session and tool call.
     *
     * @param sessionId the session requesting approval
     * @param call      the tool call that requires approval
     * @return the created pending approval
     */
    public ApprovalQueue.PendingApproval requestApproval(UUID sessionId, ToolCall call) {
        return approvalQueue.request(sessionId, call, "Approval required for tool: " + call.name());
    }
}