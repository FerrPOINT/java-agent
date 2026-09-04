package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BackendSessionResolver;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * /subgoal — Append a user-supplied criterion to the active goal.
 * /subgoal <text>        — add a subgoal
 * /subgoal               — list subgoals
 * /subgoal remove <N>    — remove subgoal by index (1-based)
 * /subgoal clear         — clear all subgoals
 * Requires an active /goal.
 */
@Component
@RequiredArgsConstructor
public class SubgoalCommand implements CommandHandler {

    private static final String GOAL_KEY = "_standingGoal";
    private static final String SUBGOALS_KEY = "_subgoals";

    private final AgentBackendClient backendClient;
    private final BotSessionStore store;

    @Override
    public String name() {
        return "subgoal";
    }

    @Override
    public String description() {
        return "Add criteria to the active goal";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) {
            return "No active session.";
        }

        String goal = session.getMetadata(GOAL_KEY);
        if (goal == null || goal.isBlank()) {
            return "No active goal. Use /goal <text> to set one first.";
        }

        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return listSubgoals(session);
        }

        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();

        String sessionId = BackendSessionResolver.resolveString(session);

        return switch (sub) {
            case "list" -> listSubgoals(session);
            case "remove" -> {
                if (parts.length < 2) yield "Usage: /subgoal remove <N>";
                try {
                    int n = Integer.parseInt(parts[1].trim());
                    String removed = removeSubgoal(session, n);
                    if (sessionId != null) backendClient.appendSubgoal(sessionId, "");
                    yield removed;
                } catch (NumberFormatException e) {
                    yield "Invalid index: " + parts[1];
                }
            }
            case "clear" -> {
                persistSubgoals(session, null);
                if (sessionId != null) backendClient.clearSubgoals(sessionId);
                yield "All subgoals cleared.";
            }
            default -> {
                String subgoalText = args.trim();
                if (subgoalText.length() > 300) {
                    yield "Subgoal text too long (max 300 chars).";
                }
                String existing = session.getMetadata(SUBGOALS_KEY);
                String updated = existing == null || existing.isBlank()
                    ? subgoalText
                    : existing + "\n" + subgoalText;
                persistSubgoals(session, updated);
                if (sessionId != null) backendClient.appendSubgoal(sessionId, subgoalText);
                yield "Subgoal added: " + subgoalText;
            }
        };
    }

    private String listSubgoals(BotSessionEntity session) {
        String subgoals = session.getMetadata(SUBGOALS_KEY);
        if (subgoals == null || subgoals.isBlank()) {
            return "No subgoals set. Use /subgoal <text> to add one.";
        }

        StringBuilder sb = new StringBuilder("Subgoals:\n");
        String[] items = subgoals.split("\n");
        for (int i = 0; i < items.length; i++) {
            if (!items[i].isBlank()) {
                sb.append("  ").append(i + 1).append(". ").append(items[i].trim()).append("\n");
            }
        }
        sb.append("\nCommands: /subgoal remove <N> | /subgoal clear");
        return sb.toString().trim();
    }

    private String removeSubgoal(BotSessionEntity session, int n) {
        String subgoals = session.getMetadata(SUBGOALS_KEY);
        if (subgoals == null || subgoals.isBlank()) {
            return "No subgoals to remove.";
        }

        String[] items = subgoals.split("\n");
        if (n < 1 || n > items.length) {
            return "Invalid index. Valid range: 1-" + items.length;
        }

        StringBuilder updated = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i != n - 1 && !items[i].isBlank()) {
                if (updated.length() > 0) updated.append("\n");
                updated.append(items[i].trim());
            }
        }

        persistSubgoals(session, updated.length() > 0 ? updated.toString() : null);
        return "Subgoal " + n + " removed.";
    }

    private void persistSubgoals(BotSessionEntity session, String value) {
        session.setMetadata(SUBGOALS_KEY, value);
        if (session.getId() != null) {
            store.persistMetadata(session.getId(), SUBGOALS_KEY, value);
        }
    }
}
