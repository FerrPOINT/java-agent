package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DiffCommand implements CommandHandler {
    private final AgentBackendClient backendClient;

    @Override
    public String name() { return "diff"; }
    @Override
    public String description() { return "Show git changes in working directory"; }
    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        String gitCmd = "git diff";
        if (args != null && !args.isBlank()) {
            if (args.contains("staged")) gitCmd = "git diff --staged";
            else if (args.contains("all")) gitCmd = "git diff HEAD";
            else if (args.contains("stat")) gitCmd = "git diff --stat";
        }
        try {
            String sessionId = session.getId() != null ? session.getId().toString() : null;
            AgentBackendClient.ChatResult result = backendClient.chat("Run: " + gitCmd + " and show me the output", sessionId);
            return result.content();
        } catch (Exception e) {
            return "Error running git diff: " + e.getMessage();
        }
    }
}