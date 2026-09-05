package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BackendSessionResolver;
import com.azhukov.agent.bot.session.BotSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CronCommand implements CommandHandler {
    private final AgentBackendClient backendClient;

    @Override
    public String name() { return "cron"; }
    @Override
    public String description() { return "Manage scheduled tasks (list, add, pause, resume, remove)"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return "Usage: /cron <action> [args]\nActions: list, add, pause <id>, resume <id>, remove <id>, run <id>";
        }
        try {
            String sessionId = BackendSessionResolver.resolveString(session);
            AgentBackendClient.ChatResult result = backendClient.chat(
                "Manage cron jobs: " + args + ". Use the cronjob tool to perform the requested action.", sessionId);
            return result.content();
        } catch (Exception e) {
            return "Error managing cron jobs: " + e.getMessage();
        }
    }
}