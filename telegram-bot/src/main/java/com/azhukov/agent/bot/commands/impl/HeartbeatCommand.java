package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes parity: /heartbeat and /loop in the Telegram bot — session-scoped
 * recurring instructions backed by the backend HeartbeatService watchdog.
 *
 * <p>/heartbeat every 10m Check the deployment — injects the prompt as a
 * normal turn whenever the session is idle and the interval elapsed.
 * /loop [interval] task [--times N] [--until cond] — recurring wakeup with
 * the LOOP_COMPLETE marker contract.
 */
@Component
@RequiredArgsConstructor
public class HeartbeatCommand implements CommandHandler {

    private final AgentBackendClient backendClient;
    private final com.azhukov.agent.bot.cron.HeartbeatDeliveryPoller deliveryPoller;

    private static final Pattern INTERVAL_TOKEN_RE =
        Pattern.compile("^(?=\\d)(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$", Pattern.CASE_INSENSITIVE);

    static Integer parseIntervalToken(String token) {
        if (token == null || token.isBlank()) return null;
        Matcher m = INTERVAL_TOKEN_RE.matcher(token);
        if (!m.matches()) return null;
        int seconds = 0;
        if (m.group(1) != null) seconds += Integer.parseInt(m.group(1)) * 3600;
        if (m.group(2) != null) seconds += Integer.parseInt(m.group(2)) * 60;
        if (m.group(3) != null) seconds += Integer.parseInt(m.group(3));
        return seconds > 0 ? seconds : null;
    }

    @Override
    public String name() {
        return "heartbeat";
    }

    @Override
    public String description() {
        return "Set a recurring prompt that re-enters this session when idle";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        java.util.UUID backend = session.getBackendSessionId();
        String sid = backend != null ? backend.toString() : session.getId().toString();
        String arg = event.commandArgs() == null ? "" : event.commandArgs().strip();
        return handleHeartbeat(arg, sid, event.chatId());
    }

    private String handleHeartbeat(String arg, String sid, long chatId) {
        String lower = arg.toLowerCase();

        if (arg.isEmpty() || lower.equals("status")) {
            JsonNode st = backendClient.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid);
            if (st == null || !st.path("set").asBoolean(false)) {
                return "No heartbeat set.\nUsage: /heartbeat every <interval> <prompt>";
            }
            return String.format("♥ Heartbeat (every %s): %s\nstatus: %s, fired %d×",
                st.path("interval").asText("?"), st.path("prompt").asText("?"),
                st.path("status").asText("?"), st.path("fireCount").asInt(0));
        }
        if (lower.equals("pause")) {
            JsonNode r = backendClient.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/pause");
            return r != null && r.path("ok").asBoolean(false)
                ? "⏸ " + r.path("message").asText("Paused.") : "No heartbeat set.";
        }
        if (lower.equals("resume")) {
            JsonNode r = backendClient.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/resume");
            return r != null && r.path("ok").asBoolean(false)
                ? "▶ " + r.path("message").asText("Resumed.") : "No heartbeat to resume.";
        }
        if (lower.equals("clear") || lower.equals("stop") || lower.equals("off")) {
            JsonNode r = backendClient.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/clear");
            return r != null && r.path("ok").asBoolean(false)
                ? "✓ Heartbeat cleared." : "No heartbeat set.";
        }

        String[] tokens = arg.split("\\s+", 3);
        Integer interval = null;
        String prompt = "";
        if (tokens.length >= 2 && tokens[0].equalsIgnoreCase("every")) {
            interval = parseIntervalToken(tokens[1]);
            prompt = tokens.length > 2 ? tokens[2] : "";
        } else {
            Integer parsed = parseIntervalToken(tokens[0]);
            if (parsed != null && parsed > 0) {
                interval = parsed;
                prompt = arg.substring(tokens[0].length()).strip();
            }
        }
        if (interval == null) {
            return "Usage: /heartbeat every <interval> <prompt>   (e.g. /heartbeat every 10m Check CI)\n"
                + "Also: /heartbeat status | pause | resume | clear";
        }
        if (interval < 60) return "Interval too small — minimum is 60s.";
        if (prompt.isBlank()) return "The prompt is required: /heartbeat every 10m <what to do>";

        JsonNode r = backendClient.suggestionPostJson("/api/v1/agent/cron/heartbeat",
            Map.of("sessionId", sid, "prompt", prompt, "intervalSeconds", interval));
        if (r == null || !r.path("ok").asBoolean(false)) {
            return "Invalid heartbeat: " + (r == null ? "backend unavailable" : r.path("reason").asText("rejected"));
        }
        // Deliver subsequent fire results to this chat (Hermes: the wakeup
        // watcher forwards each tick's reply to the user).
        deliveryPoller.watch(java.util.UUID.fromString(sid), chatId);
        return "♥ " + r.path("message").asText("Heartbeat set.")
            + "\nFires as a normal turn when the session is idle. Use /cron for durable schedules.";
    }
}
