package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes parity (hermes_cli/heartbeat.py): a session-scoped recurring
 * instruction. When the session is idle and the interval has elapsed, the
 * watchdog injects the prompt as a NORMAL user turn.
 *
 * <p>Session-scoped and in-process — for durable cross-process schedules
 * the cron service exists. Interval parsing, formatting, the state machine
 * (active/paused/cleared) and the prompt template mirror Hermes exactly.
 */
@Service
@Slf4j
public class HeartbeatService {

    public static final int MIN_INTERVAL_SECONDS = 60;
    static final double POLL_SECONDS = 5.0;

    /** Hermes HEARTBEAT_PROMPT_TEMPLATE — byte-exact. */
    static final String HEARTBEAT_PROMPT_TEMPLATE =
        "[Heartbeat — recurring instruction, fires every %s]\n%s\n\n"
            + "If there is nothing meaningful to do or report for this instruction "
            + "right now, reply briefly that nothing has changed and stop — do not "
            + "invent work.";

    private static final Pattern INTERVAL_RE = Pattern.compile(
        "^\\s*(?:every\\s+)?(\\d+(?:\\.\\d+)?)\\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)\\s*$",
        Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> UNIT_SECONDS = Map.ofEntries(
        Map.entry("s", 1), Map.entry("sec", 1), Map.entry("secs", 1),
        Map.entry("second", 1), Map.entry("seconds", 1),
        Map.entry("m", 60), Map.entry("min", 60), Map.entry("mins", 60),
        Map.entry("minute", 60), Map.entry("minutes", 60),
        Map.entry("h", 3600), Map.entry("hr", 3600), Map.entry("hrs", 3600),
        Map.entry("hour", 3600), Map.entry("hours", 3600),
        Map.entry("d", 86400), Map.entry("day", 86400), Map.entry("days", 86400));

    /** Serializable per-session heartbeat (Hermes HeartbeatState). */
    public record HeartbeatState(
        String prompt,
        int intervalSeconds,
        String status,          // active | paused | cleared
        Instant createdAt,
        Instant lastFiredAt,
        int fireCount
    ) {
        public boolean isDue(Instant now) {
            if (!"active".equals(status) || prompt == null || prompt.isBlank() || intervalSeconds <= 0) {
                return false;
            }
            Instant anchor = lastFiredAt != null ? lastFiredAt : createdAt;
            if (anchor == null) return false;
            return now.isAfter(anchor.plusSeconds(intervalSeconds));
        }
    }

    private final Map<UUID, HeartbeatState> states = new ConcurrentHashMap<>();

    /** Who to call when a heartbeat fires — wired by the runtime. */
    public interface FireTarget {
        void fire(UUID sessionId, String prompt);
    }

    private volatile FireTarget fireTarget;
    private volatile boolean watchdogRunning;

    @Autowired(required = false)
    private AgentRuntimeService agentRuntimeService;

    public void setFireTarget(FireTarget target) {
        this.fireTarget = target;
    }

    // ── Interval parsing (Hermes parse_interval) ──

    /** Parse "10m" / "every 2h" / "every 90 minutes" into seconds.
     * Returns null when not an interval, -1 when below the minimum. */
    public static Integer parseInterval(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = INTERVAL_RE.matcher(text);
        if (!m.matches()) return null;
        double value = Double.parseDouble(m.group(1));
        int seconds = (int) (value * UNIT_SECONDS.get(m.group(2).toLowerCase()));
        if (seconds < MIN_INTERVAL_SECONDS) return -1;
        return seconds;
    }

    /** Human-readable interval (600 → "10m"). Hermes format_interval. */
    public static String formatInterval(int seconds) {
        if (seconds % 86400 == 0) return (seconds / 86400) + "d";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    // ── State management ──

    public synchronized HeartbeatState set(UUID sessionId, String prompt, int intervalSeconds) {
        HeartbeatState st = new HeartbeatState(prompt.strip(), intervalSeconds, "active",
            Instant.now(), null, 0);
        states.put(sessionId, st);
        ensureWatchdog();
        return st;
    }

    public synchronized HeartbeatState pause(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null || !"active".equals(st.status())) return null;
        st = new HeartbeatState(st.prompt(), st.intervalSeconds(), "paused",
            st.createdAt(), st.lastFiredAt(), st.fireCount());
        states.put(sessionId, st);
        return st;
    }

    public synchronized HeartbeatState resume(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null || !"paused".equals(st.status())) return null;
        st = new HeartbeatState(st.prompt(), st.intervalSeconds(), "active",
            st.createdAt(), Instant.now(), st.fireCount());  // anchor reset — Hermes resume restarts the clock
        states.put(sessionId, st);
        ensureWatchdog();
        return st;
    }

    public synchronized boolean clear(UUID sessionId) {
        return states.remove(sessionId) != null;
    }

    public HeartbeatState get(UUID sessionId) {
        return states.get(sessionId);
    }

    public String statusLine(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null) return "No heartbeat set.";
        String verb = "paused".equals(st.status()) ? "Paused heartbeat" : "Heartbeat";
        return String.format("%s (every %s): %s", verb, formatInterval(st.intervalSeconds()), st.prompt());
    }

    /** Build the injected user-turn text (Hermes HEARTBEAT_PROMPT_TEMPLATE). */
    public String buildFirePrompt(HeartbeatState st) {
        return String.format(HEARTBEAT_PROMPT_TEMPLATE, formatInterval(st.intervalSeconds()), st.prompt());
    }

    // ── Watchdog ──

    private void ensureWatchdog() {
        if (watchdogRunning) return;
        synchronized (this) {
            if (watchdogRunning) return;
            watchdogRunning = true;
        }
        Thread t = new Thread(this::watchdogLoop, "heartbeat-watchdog");
        t.setDaemon(true);
        t.start();
        log.info("Heartbeat watchdog started (poll every {}s)", POLL_SECONDS);
    }

    private void watchdogLoop() {
        while (true) {
            try {
                Thread.sleep((long) (POLL_SECONDS * 1000));
                Instant now = Instant.now();
                for (Map.Entry<UUID, HeartbeatState> e : states.entrySet()) {
                    HeartbeatState st = e.getValue();
                    if (!st.isDue(now)) continue;
                    // Mark fired FIRST so a slow target can't double-fire
                    HeartbeatState fired = new HeartbeatState(st.prompt(), st.intervalSeconds(),
                        st.status(), st.createdAt(), Instant.now(), st.fireCount() + 1);
                    states.put(e.getKey(), fired);
                    String prompt = buildFirePrompt(fired);
                    log.info("Heartbeat firing for session {} (fire #{}): {}",
                        e.getKey(), fired.fireCount(), st.prompt());
                    FireTarget target = this.fireTarget;
                    if (target != null) {
                        try {
                            target.fire(e.getKey(), prompt);
                        } catch (Exception ex) {
                            log.warn("Heartbeat fire failed for session {}: {}", e.getKey(), ex.getMessage());
                        }
                    } else if (agentRuntimeService != null) {
                        try {
                            agentRuntimeService.runHeartbeatTurn(e.getKey(), prompt);
                        } catch (Exception ex) {
                            log.warn("Heartbeat turn failed for session {}: {}", e.getKey(), ex.getMessage());
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Heartbeat watchdog tick failed: {}", e.getMessage());
            }
        }
    }
}
