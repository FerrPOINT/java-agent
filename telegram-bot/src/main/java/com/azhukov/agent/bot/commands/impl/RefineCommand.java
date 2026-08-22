package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Hermes parity (/refine, gateway slash_commands.py): run the memory/skill
 * background review on demand with optional focus. The review fork runs in
 * the backend; memory/skill updates are reported when done — this turn is
 * never blocked.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefineCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    @Override
    public String name() {
        return "refine";
    }

    @Override
    public String description() {
        return "Review this conversation now and save lessons to memory/skills";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        java.util.UUID backend = session.getBackendSessionId();
        if (backend == null) {
            return "Nothing to refine yet — send a message first.";
        }
        String focus = event.commandArgs() == null ? "" : event.commandArgs().strip();

        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", backend.toString());
        if (!focus.isBlank()) {
            body.put("focus", focus);
        }
        JsonNode resp = backendClient.suggestionPostJson("/api/v1/agent/refine", body);
        if (resp == null) {
            return "/refine failed to start: backend unavailable.";
        }
        if (!resp.path("accepted").asBoolean(false)) {
            return "/refine failed to start: " + resp.path("reason").asText("unknown reason");
        }
        return "⚗ " + resp.path("message").asText(
            "Reviewing this conversation in the background — updates reported when done.");
    }
}
