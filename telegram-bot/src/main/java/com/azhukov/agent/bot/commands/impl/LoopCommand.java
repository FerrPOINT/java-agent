package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hermes parity (hermes_cli/loops.py): /loop — recurring in-session wakeups.
 * Forms: /loop [interval] task [--times N] [--until cond] | status | pause |
 * resume | stop. Backed by the HeartbeatService watchdog; the wakeup prompt
 * carries the LOOP_COMPLETE marker contract.
 */
@Component
@RequiredArgsConstructor
public class LoopCommand implements CommandHandler {

    public static final String LOOP_COMPLETE_MARKER = "LOOP_COMPLETE";

    private final AgentBackendClient backendClient;
    private final com.azhukov.agent.bot.cron.HeartbeatDeliveryPoller deliveryPoller;

    @Override
    public String name() {
        return "loop";
    }

    @Override
    public String description() {
        return "Re-run a prompt on a recurring interval in this session";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        java.util.UUID backend = session.getBackendSessionId();
        String sid = backend != null ? backend.toString() : session.getId().toString();
        String arg = event.commandArgs() == null ? "" : event.commandArgs().strip();
        String lower = arg.toLowerCase();

        if (arg.isEmpty() || lower.equals("status")) {
            return heartbeatStatus(sid);
        }
        if (lower.equals("pause") || lower.equals("resume") || lower.equals("stop") || lower.equals("off")) {
            JsonNode r = backendClient.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid
                + "/" + ("off".equals(lower) ? "clear" : lower));
            if ("off".equals(lower) || "stop".equals(lower)) {
                deliveryPoller.unwatch(java.util.UUID.fromString(sid));
            }
            return r != null && r.path("ok").asBoolean(false)
                ? r.path("message").asText("Done.") : "No loop set.";
        }

        Integer times = null;
        String until = null;
        List<String> rest = new ArrayList<>(List.of(arg.split("\\s+")));
        for (int i = 0; i < rest.size(); i++) {
            if (rest.get(i).equalsIgnoreCase("--times") && i + 1 < rest.size()) {
                try { times = Integer.parseInt(rest.get(i + 1)); } catch (NumberFormatException ignored) {}
                rest.remove(i); rest.remove(i); i--;
            } else if (rest.get(i).equalsIgnoreCase("--until") && i + 1 < rest.size()) {
                until = String.join(" ", rest.subList(i + 1, rest.size()));
                rest.subList(i, rest.size()).clear();
                break;
            }
        }
        String joined = String.join(" ", rest).strip();
        Integer interval = null;
        String prompt = joined;
        String first = joined.isEmpty() ? "" : joined.split("\\s+")[0];
        Integer parsed = HeartbeatCommand.parseIntervalToken(first);
        if (parsed != null) {
            interval = parsed;
            prompt = joined.substring(first.length()).strip();
        }
        if (prompt.isBlank()) {
            return "Usage: /loop [interval] <prompt> [--times N] [--until <condition>]\n"
                + "Also: /loop status | pause | resume | stop";
        }
        int effective = interval != null ? Math.max(interval, 60) : 300;

        StringBuilder loopPrompt = new StringBuilder();
        loopPrompt.append("[/loop]").append(times != null ? " (max " + times + " iterations)" : "")
            .append("\nRecurring task: ").append(prompt).append("\n");
        if (until != null) {
            loopPrompt.append("\nStop condition: ").append(until).append("\n");
        }
        loopPrompt.append("\nPerform the task now against the CURRENT state (re-check files, ")
            .append("processes, or services fresh — do not assume anything from earlier ")
            .append("iterations still holds). Report concisely what you found or did.\n")
            .append("If the task is complete, no longer applicable, or the stop condition is met, ")
            .append("say so and end your reply with ").append(LOOP_COMPLETE_MARKER)
            .append(" on its own line — that stops the loop.");

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("sessionId", sid);
        body.put("prompt", loopPrompt.toString());
        body.put("intervalSeconds", effective);
        if (times != null) body.put("maxTicks", times);
        JsonNode r = backendClient.suggestionPostJson("/api/v1/agent/cron/heartbeat", body);
        if (r == null || !r.path("ok").asBoolean(false)) {
            return "/loop failed: " + (r == null ? "backend unavailable" : r.path("reason").asText("rejected"));
        }
        deliveryPoller.watch(java.util.UUID.fromString(sid), event.chatId());
        return "🔁 Loop set (every " + (interval != null ? interval + "s" : "5m") + "): " + prompt + "\n"
            + (times != null ? "Max " + times + " iterations.\n" : "")
            + (until != null ? "Stop condition: " + until + "\n" : "")
            + "End a reply with LOOP_COMPLETE on its own line to stop.";
    }

    private String heartbeatStatus(String sid) {
        JsonNode st = backendClient.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid);
        if (st == null || !st.path("set").asBoolean(false)) {
            return "No loop set.\nUsage: /loop [interval] <prompt> [--times N] [--until <condition>]";
        }
        return String.format("🔁 Loop (every %s): %s\nstatus: %s, fired %d×",
            st.path("interval").asText("?"), st.path("prompt").asText("?"),
            st.path("status").asText("?"), st.path("fireCount").asInt(0));
    }
}
