package com.azhukov.agent.service;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes parity (hermes_cli/heartbeat.py + hermes_cli/loops.py): session-scoped
 * recurring instructions. When the session is IDLE and the interval elapsed,
 * the watchdog injects the prompt as a NORMAL user turn — never interleaving
 * with a user's turn (a real user message always wins; ticks coalesce).
 *
 * <p>Loop semantics (/loop): the wakeup prompt teaches the agent to end with
 * LOOP_COMPLETE on its own line; the watchdog scans each response and stops
 * the loop when it appears, or when the --times/max-ticks cap is hit.
 *
 * <p>Session-scoped and in-process — for durable cross-process schedules the
 * cron service exists. Interval parsing/formatting, the state machine
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

    /** Hermes _LOOP_COMPLETE_RE: marker on its own line, optional trailing .! */
    static final Pattern LOOP_COMPLETE_RE = Pattern.compile(
        "(?im)^\\s*LOOP_COMPLETE\\s*[.!]?\\s*$");

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
        int fireCount,
        int maxTicks           // /loop --times N; 0 = unlimited
    ) {
        public HeartbeatState(String prompt, int intervalSeconds, String status,
                               Instant createdAt, Instant lastFiredAt, int fireCount) {
            this(prompt, intervalSeconds, status, createdAt, lastFiredAt, fireCount, 0);
        }

        public boolean isDue(Instant now) {
            if (!"active".equals(status) || prompt == null || prompt.isBlank() || intervalSeconds <= 0) {
                return false;
            }
            if (maxTicks > 0 && fireCount >= maxTicks) {
                return false;   // --times cap reached — loop is finished
            }
            Instant anchor = lastFiredAt != null ? lastFiredAt : createdAt;
            if (anchor == null) return false;
            return now.isAfter(anchor.plusSeconds(intervalSeconds));
        }
    }

    /** Hermes response_signals_complete (loops.py:505-508). */
    public static boolean responseSignalsComplete(String response) {
        if (response == null || response.isBlank()) return false;
        return LOOP_COMPLETE_RE.matcher(response).find();
    }

    private final Map<UUID, HeartbeatState> states = new ConcurrentHashMap<>();

    /** Last fired result per session, for delivery polling (chat delivery). */
    private final Map<UUID, String> lastFireResults = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Lazy
    private AgentRuntimeService agentRuntimeService;

    @Autowired(required = false)
    private AgentRuntime agentRuntime;

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
        return set(sessionId, prompt, intervalSeconds, 0);
    }

    public synchronized HeartbeatState set(UUID sessionId, String prompt, int intervalSeconds, int maxTicks) {
        HeartbeatState st = new HeartbeatState(prompt.strip(), intervalSeconds, "active",
            Instant.now(), null, 0, maxTicks);
        states.put(sessionId, st);
        ensureWatchdog();
        return st;
    }

    public synchronized HeartbeatState pause(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null || !"active".equals(st.status())) return null;
        st = new HeartbeatState(st.prompt(), st.intervalSeconds(), "paused",
            st.createdAt(), st.lastFiredAt(), st.fireCount(), st.maxTicks());
        states.put(sessionId, st);
        return st;
    }

    public synchronized HeartbeatState resume(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null || !"paused".equals(st.status())) return null;
        st = new HeartbeatState(st.prompt(), st.intervalSeconds(), "active",
            st.createdAt(), Instant.now(), st.fireCount(), st.maxTicks());  // anchor reset — Hermes resume restarts the clock
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

    private volatile boolean watchdogRunning;

    private void watchdogLoop() {
        while (true) {
            try {
                Thread.sleep((long) (POLL_SECONDS * 1000));
                Instant now = Instant.now();
                for (Map.Entry<UUID, HeartbeatState> e : states.entrySet()) {
                    try {
                        tick(e.getKey(), e.getValue(), now);
                    } catch (Exception ex) {
                        log.warn("Heartbeat tick failed for session {}: {}", e.getKey(), ex.getMessage());
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

    /** One due-check for one session. Package-private for tests. */
    void tick(UUID sessionId, HeartbeatState st, Instant now) {
        if (!st.isDue(now)) return;

        // Hermes: heartbeats only fire into an IDLE session. If the session is
        // busy (user turn in progress), coalesce — do NOT advance the anchor.
        if (isBusy(sessionId)) {
            log.debug("Heartbeat due for session {} but session busy — coalescing", sessionId);
            return;
        }

        // Mark fired FIRST so a slow target can't double-fire
        HeartbeatState fired = new HeartbeatState(st.prompt(), st.intervalSeconds(),
            st.status(), st.createdAt(), Instant.now(), st.fireCount() + 1, st.maxTicks());
        states.put(sessionId, fired);
        String prompt = buildFirePrompt(fired);
        log.info("Heartbeat firing for session {} (fire #{}): {}",
            sessionId, fired.fireCount(), st.prompt());
        String response = null;
        try {
            response = agentRuntimeService != null
                ? agentRuntimeService.runHeartbeatTurn(sessionId, prompt)
                : null;
        } catch (Exception ex) {
            log.warn("Heartbeat turn failed for session {}: {}", sessionId, ex.getMessage());
        }
        if (response != null && !response.isBlank()) {
            lastFireResults.put(sessionId, response);
        }
        // /loop contract: LOOP_COMPLETE in the response stops the loop.
        if (responseSignalsComplete(response)) {
            log.info("Heartbeat/loop for session {} signalled LOOP_COMPLETE — clearing", sessionId);
            states.remove(sessionId);
        } else if (fired.maxTicks() > 0 && fired.fireCount() >= fired.maxTicks()) {
            log.info("Heartbeat/loop for session {} reached --times cap ({}); clearing", sessionId, fired.maxTicks());
            states.remove(sessionId);
        }
    }

    /** Last fired result for delivery polling; null when nothing new. Clears on read. */
    public String pollLastFireResult(UUID sessionId) {
        return lastFireResults.remove(sessionId);
    }

    /** Busy-check seam (package-private for test overrides). */
    boolean isBusy(UUID sessionId) {
        return agentRuntime != null && agentRuntime.isSessionBusy(sessionId);
    }
}
