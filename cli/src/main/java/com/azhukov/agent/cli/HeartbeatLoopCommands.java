package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes parity: /heartbeat and /loop slash commands.
 *
 * <p>Both are session-scoped recurring instructions driven by the backend
 * watchdog (HeartbeatService): /heartbeat is the plain recurring form, /loop
 * adds --times N and --until &lt;condition&gt; with the LOOP_COMPLETE marker
 * contract (hermes_cli/loops.py WAKEUP_PROMPT_TEMPLATE).
 */
public final class HeartbeatLoopCommands implements CommandGroup {

    public static final String LOOP_COMPLETE_MARKER = "LOOP_COMPLETE";

    private static final Pattern INTERVAL_TOKEN_RE =
        Pattern.compile("^(?=\\d)(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$", Pattern.CASE_INSENSITIVE);

    /** Parse a compound interval token: 30s / 5m / 2h / 1h30m. Returns seconds or null. */
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
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("heartbeat", "Set a recurring prompt that re-enters this session when idle",
            (args, client, sessionId) -> handleHeartbeat(args == null ? "" : args.strip(), client, sessionId));
        registry.register("loop", "Re-run a prompt on a recurring interval in this session",
            (args, client, sessionId) -> handleLoop(args == null ? "" : args.strip(), client, sessionId));
    }

    // ── /heartbeat [status | pause | resume | clear | every <interval> <prompt>] ──

    private String handleHeartbeat(String arg, BackendClient client, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "Heartbeats unavailable — no active session.";
        }
        String sid = sessionId.strip();
        String lower = arg.toLowerCase();

        if (arg.isEmpty() || lower.equals("status")) {
            return client.executeGet("/api/v1/agent/cron/heartbeat/" + sid)
                .path("set").asBoolean(false)
                    ? heartbeatStatusText(client, sid)
                    : "No heartbeat set. Usage: /heartbeat every <interval> <prompt>";
        }
        if (lower.equals("pause")) {
            JsonNode r = client.executePost("/api/v1/agent/cron/heartbeat/" + sid + "/pause", java.util.Map.of());
            return r.path("ok").asBoolean(false) ? "⏸ " + r.path("message").asText("Paused.")
                                                 : "No heartbeat set.";
        }
        if (lower.equals("resume")) {
            JsonNode r = client.executePost("/api/v1/agent/cron/heartbeat/" + sid + "/resume", java.util.Map.of());
            return r.path("ok").asBoolean(false) ? "▶ " + r.path("message").asText("Resumed.")
                                                 : "No heartbeat to resume.";
        }
        if (lower.equals("clear") || lower.equals("stop") || lower.equals("off")) {
            JsonNode r = client.executePost("/api/v1/agent/cron/heartbeat/" + sid + "/clear", java.util.Map.of());
            return r.path("ok").asBoolean(false) ? "✓ Heartbeat cleared." : "No heartbeat set.";
        }

        // Set: `every 10m <prompt>` (also bare `10m <prompt>`)
        String[] tokens = arg.split("\\s+", 3);
        Integer interval = null;
        String prompt = "";
        if (tokens.length >= 2 && tokens[0].equalsIgnoreCase("every")) {
            Integer parsed = parseIntervalToken(tokens[1]);
            interval = parsed;
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
        if (interval < 60) {
            return "Interval too small — minimum is 60s.";
        }
        if (prompt.isBlank()) {
            return "Usage: /heartbeat every <interval> <prompt> — the prompt is required.";
        }
        JsonNode r = client.executePost("/api/v1/agent/cron/heartbeat",
            java.util.Map.of("sessionId", sid, "prompt", prompt, "intervalSeconds", interval));
        if (!r.path("ok").asBoolean(false)) {
            return "Invalid heartbeat: " + r.path("reason").asText("rejected");
        }
        return "♥ " + r.path("message").asText("Heartbeat set.")
            + "\nFires as a normal turn whenever the session is idle and the interval has elapsed."
            + " /heartbeat pause | resume | clear to manage; lives only while this backend runs —"
            + " use /cron for durable schedules.";
    }

    private String heartbeatStatusText(BackendClient client, String sid) {
        JsonNode st = client.executeGet("/api/v1/agent/cron/heartbeat/" + sid);
        return String.format("Heartbeat (every %s): %s — status %s, fired %d×",
            st.path("interval").asText("?"), st.path("prompt").asText("?"),
            st.path("status").asText("?"), st.path("fireCount").asInt(0));
    }

    // ── /loop [interval] <prompt> [--times N] [--until <cond>] | status | pause | resume | stop ──

    private String handleLoop(String arg, BackendClient client, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "Loops unavailable — no active session.";
        }
        String sid = sessionId.strip();
        String lower = arg.toLowerCase();

        if (arg.isEmpty() || lower.equals("status")) {
            return handleHeartbeat("status", client, sid);
        }
        if (lower.equals("pause") || lower.equals("resume") || lower.equals("stop") || lower.equals("off")) {
            return handleHeartbeat(lower.equals("off") ? "clear" : lower, client, sid);
        }

        // Parse: [interval] <prompt> [--times N] [--until <cond>]
        Integer times = null;
        String until = null;
        java.util.List<String> rest = new java.util.ArrayList<>(java.util.List.of(arg.split("\\s+")));
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
        Integer parsed = parseIntervalToken(first);
        if (parsed != null) {
            interval = parsed;
            prompt = joined.substring(first.length()).strip();
        }
        if (prompt.isBlank()) {
            return "Usage: /loop [interval] <prompt> [--times N] [--until <condition>]\n"
                + "Also: /loop status | pause | resume | stop";
        }
        int effective = interval != null ? Math.max(interval, 60) : 300;  // Hermes default cadence 5m

        // Build the loop wakeup prompt with the LOOP_COMPLETE contract.
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

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("sessionId", sid);
        body.put("prompt", loopPrompt.toString());
        body.put("intervalSeconds", effective);
        if (times != null) body.put("maxTicks", times);
        JsonNode r = client.executePost("/api/v1/agent/cron/heartbeat", body);
        if (!r.path("ok").asBoolean(false)) {
            return "/loop failed: " + r.path("reason").asText("rejected");
        }
        return "🔁 Loop set (every " + (interval != null ? interval + "s" : "5m") + "): " + prompt + "\n"
            + (times != null ? "Max " + times + " iterations. " : "")
            + (until != null ? "Stop condition: " + until + "\n" : "")
            + "End a reply with LOOP_COMPLETE on its own line to stop. /loop stop to cancel.";
    }
}
