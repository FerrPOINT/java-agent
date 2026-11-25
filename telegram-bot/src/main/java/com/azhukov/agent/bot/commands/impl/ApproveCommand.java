package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.shared.dto.ToolApprovalDecision;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ApproveCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() {
        return "approve";
    }

    @Override
    public String description() {
        return "Approve pending command";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        try {
            return backendClient.decideApproval(ToolApprovalDecision.fromCommand(
                ToolApprovalDecision.Action.APPROVE, event.commandArgs()));
        } catch (IllegalArgumentException e) {
            return "Usage: /approve [requestId|all]";
        }
    }
}
