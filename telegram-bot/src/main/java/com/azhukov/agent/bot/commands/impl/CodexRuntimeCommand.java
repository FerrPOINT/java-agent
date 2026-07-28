package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A3.6: /codex_runtime — Codex runtime (stub, not supported in this build).
 */
@Component
public class CodexRuntimeCommand implements CommandHandler {

    @Override
    public String name() {
        return "codex_runtime";
    }

    @Override
    public String description() {
        return "Codex runtime (not supported)";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return "Codex runtime is not supported in this build.";
    }
}