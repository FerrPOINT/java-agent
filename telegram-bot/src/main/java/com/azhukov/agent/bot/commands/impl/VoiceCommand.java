package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.springframework.stereotype.Component;

/**
 * /voice on|off|status — Toggle voice mode for TTS responses.
 * When voice mode is on, agent responses are automatically converted to voice messages.
 */
@Component
public class VoiceCommand implements CommandHandler {

    private final BotSessionStore sessionStore;

    public VoiceCommand(BotSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public String name() {
        return "voice";
    }

    @Override
    public String description() {
        return "Voice mode: /voice on, /voice off, /voice status";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null || session.getId() == null) {
            return "Session not found. Please start a new conversation.";
        }

        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            args = "status";
        }

        args = args.trim().toLowerCase();

        return switch (args) {
            case "on", "enable", "true" -> {
                sessionStore.setVoiceMode(session.getId(), true);
                yield "🔊 Voice mode enabled. Responses will be sent as voice messages.";
            }
            case "off", "disable", "false" -> {
                sessionStore.setVoiceMode(session.getId(), false);
                yield "🔇 Voice mode disabled. Responses will be sent as text.";
            }
            case "status", "" -> {
                boolean enabled = session.isVoiceMode();
                yield enabled
                    ? "🔊 Voice mode is currently ON."
                    : "🔇 Voice mode is currently OFF.";
            }
            default -> "Usage: /voice on|off|status";
        };
    }
}