package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BackendSessionResolver;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StatusCommand implements CommandHandler {

    private static final int MAX_CONTEXT_CHARS = 64000;

    private final BotProperties properties;
    private final AgentBackendClient backendClient;


    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show current session status";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "No active session.";
        }

        String model = session.getModelOverride();
        if (model == null || model.isBlank()) {
            model = properties.getDefaultModel();
        }
        if (model == null || model.isBlank()) {
            model = "default";
        }

        String contextFill = "unknown";
        String sid = BackendSessionResolver.resolveString(session);
        if (sid == null) return "No backend session yet — send a message first.";
        JsonNode ctx = backendClient.getContext(sid);
        if (ctx != null) {
            int tokenEstimate = ctx.path("tokenEstimate").asInt(0);
            int messageCount = ctx.path("messageCount").asInt(0);
            if (messageCount > 0) {
                int pct = (int) ((long) tokenEstimate * 100 / MAX_CONTEXT_CHARS);
                contextFill = pct + "%";
            }
        }

        return "Session status:\n"
            + "  Agent: " + properties.getAgentName() + "\n"
            + "  Model: " + model + "\n"
            + "  Working directory: " + properties.getWorkingDirectory() + "\n"
            + "  Context fill: " + contextFill + "\n"
            + "  YOLO: " + session.isYoloMode() + "\n"
            + "  Verbose: " + session.isVerboseMode() + "\n"
            + "  Fast: " + session.isFastMode() + "\n"
            + "  Reasoning: " + session.getReasoningLevel();
    }
}