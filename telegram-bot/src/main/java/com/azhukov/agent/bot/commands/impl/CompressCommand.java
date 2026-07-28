package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * /compress — Compress session context.
 * /compress <focus> — full compress with focus topic
 * /compress here <N> — partial compress keeping last N exchanges
 */
@Component
public class CompressCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    public CompressCommand(AgentBackendClient backendClient) {
        this.backendClient = backendClient;
    }

    @Override
    public String name() {
        return "compress";
    }

    @Override
    public String description() {
        return "Compress session context";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) return "No active session.";
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return backendClient.compressSession(session.getId().toString(), null);
        }

        // Check for "here <N>" pattern for partial compression
        String trimmed = args.trim();
        if (trimmed.toLowerCase().startsWith("here ")) {
            String nStr = trimmed.substring(5).trim();
            try {
                int keepLastN = Integer.parseInt(nStr);
                return backendClient.compressSessionPartial(session.getId().toString(), keepLastN);
            } catch (NumberFormatException e) {
                return "Invalid number for 'here': " + nStr;
            }
        }

        // Regular compress with focus topic
        return backendClient.compressSession(session.getId().toString(), trimmed);
    }
}