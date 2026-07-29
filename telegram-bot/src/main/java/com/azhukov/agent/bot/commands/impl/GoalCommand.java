package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /goal — Set a standing goal the agent works toward across turns.
 * /goal <text>       — set goal
 * /goal status       — show current goal and subgoals
 * /goal pause        — pause goal
 * /goal resume       — resume goal
 * /goal clear        — clear goal and all subgoals
 *
 * Note: Unlike Hermes, this build does not auto-continue with a judge model.
 * The goal is stored in the session and included in the system prompt context.
 */
@Component
public class GoalCommand implements CommandHandler {

    private static final String GOAL_KEY = "_standingGoal";
    private static final String GOAL_PAUSED_KEY = "_standingGoalPaused";
    private static final String SUBGOALS_KEY = "_subgoals";

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return "Set or manage a standing goal";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        if (session == null) {
            return "No active session.";
        }

        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return showStatus(session);
        }

        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase();

        return switch (sub) {
            case "status" -> showStatus(session);
            case "pause" -> {
                session.setMetadata(GOAL_PAUSED_KEY, "true");
                yield "Goal paused. Use /goal resume to continue.";
            }
            case "resume" -> {
                session.setMetadata(GOAL_PAUSED_KEY, "false");
                yield "Goal resumed.";
            }
            case "clear" -> {
                session.setMetadata(GOAL_KEY, null);
                session.setMetadata(GOAL_PAUSED_KEY, null);
                session.setMetadata(SUBGOALS_KEY, null);
                yield "Goal and all subgoals cleared.";
            }
            default -> {
                String goalText = args.trim();
                if (goalText.length() > 500) {
                    yield "Goal text too long (max 500 chars).";
                }
                session.setMetadata(GOAL_KEY, goalText);
                session.setMetadata(GOAL_PAUSED_KEY, "false");
                yield "Goal set: " + goalText + "\n\nUse /subgoal to add criteria. Use /goal clear to remove.";
            }
        };
    }

    private String showStatus(BotSessionEntity session) {
        String goal = session.getMetadata(GOAL_KEY);
        if (goal == null || goal.isBlank()) {
            return "No standing goal set.\n\nUsage: /goal <text> — set a goal";
        }

        String paused = session.getMetadata(GOAL_PAUSED_KEY);
        boolean isPaused = "true".equals(paused);

        StringBuilder sb = new StringBuilder("Standing goal:\n");
        sb.append("  ").append(goal).append("\n");
        sb.append("  Status: ").append(isPaused ? "paused" : "active").append("\n");

        String subgoals = session.getMetadata(SUBGOALS_KEY);
        if (subgoals != null && !subgoals.isBlank()) {
            sb.append("  Subgoals:\n");
            for (String sg : subgoals.split("\n")) {
                if (!sg.isBlank()) sb.append("    - ").append(sg).append("\n");
            }
        }

        sb.append("\nCommands: /goal pause | /goal resume | /goal clear | /subgoal <text>");
        return sb.toString().trim();
    }

    /**
     * Get the active goal text for system prompt injection, or null if no goal set or paused.
     */
    public static String getActiveGoal(BotSessionEntity session) {
        if (session == null) return null;
        String goal = session.getMetadata(GOAL_KEY);
        if (goal == null || goal.isBlank()) return null;
        String paused = session.getMetadata(GOAL_PAUSED_KEY);
        if ("true".equals(paused)) return null;
        return goal;
    }

    /**
     * Get the list of subgoals for system prompt injection, or empty list if none.
     */
    public static List<String> getSubgoals(BotSessionEntity session) {
        if (session == null) return List.of();
        String subgoals = session.getMetadata(SUBGOALS_KEY);
        if (subgoals == null || subgoals.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String sg : subgoals.split("\n")) {
            if (!sg.isBlank()) result.add(sg.trim());
        }
        return result;
    }
}