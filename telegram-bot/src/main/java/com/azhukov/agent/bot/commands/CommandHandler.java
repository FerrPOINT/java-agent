package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;

/**
 * Handler for a single slash command.
 */
public interface CommandHandler {

    /** Command name without the leading slash, e.g. {@code "new"}. */
    String name();

    /** Human-readable description shown in /help. */
    String description();

    /** Process the command and return the text response. */
    String handle(UpdateEvent event, BotSessionEntity session);
}