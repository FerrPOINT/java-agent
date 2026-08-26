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

    /** Delivery attempts per pending result — after 5 failed sends we drop it
     *  (a poisoned result must not clog the channel forever). */
    private final Map<UUID, java.util.concurrent.atomic.AtomicInteger> deliveryAttempts =
        new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Lazy
    private AgentRuntimeService agentRuntimeService;

    @Autowired(required = false)
    private com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;

    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** Hermes persists heartbeat state in SessionDB state_meta (heartbeat:<id>)
     *  so /resume picks it up after a restart. We persist in session_cli_state. */
    private static final String STATE_KEY = "heartbeat";
    /** Field separator for the persisted state (prompt may contain anything else). */
    private static final String SEP = "\u0001";

    /**
     * cliState is a LAZY @ElementCollection: touching the detached entity's map
     * outside a persistence context throws LazyInitializationException now that
     * OSIV is off. A @Transactional annotation would NOT help — persist() is
     * private and self-invoked (proxy never engaged), so the write runs inside
     * an explicit TransactionTemplate block instead. Short write-only tx, no
     * LLM calls inside — pool-safe.
     */
    private void persist(UUID sessionId, HeartbeatState st) {
        if (sessionRepository == null) return;
        Runnable write = () -> sessionRepository.findById(sessionId).ifPresent(e -> {
            if (st == null) {
                e.getCliState().remove(STATE_KEY);
            } else {
                e.setCliStateValue(STATE_KEY, String.join(SEP,
                    st.prompt(), String.valueOf(st.intervalSeconds()), st.status(),
                    String.valueOf(st.maxTicks()), String.valueOf(st.fireCount())));
            }
            sessionRepository.save(e);
        });
        try {
            if (transactionTemplate != null) {
                transactionTemplate.executeWithoutResult(tx -> write.run());
            } else {
                write.run();
            }
        } catch (Exception ex) {
            log.warn("Heartbeat persist failed for {}: {}", sessionId, ex.getMessage());
        }
    }

    /** Restore persisted heartbeats at startup (Hermes /resume picks up state_meta). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void restorePersisted() {
        if (jdbcTemplate == null) return;
        try {
            int restored = 0;
            var rows = jdbcTemplate.queryForList(
                "SELECT session_id, state_value FROM session_cli_state WHERE state_key = ?", STATE_KEY);
            for (var row : rows) {
                UUID sid = java.util.UUID.fromString(String.valueOf(row.get("session_id")));
                String raw = String.valueOf(row.get("state_value"));
                if (raw == null || raw.isBlank()) continue;
                String[] parts = raw.split(Pattern.quote(SEP), -1);
                if (parts.length < 5) continue;
                try {
                    HeartbeatState st = new HeartbeatState(parts[0],
                        Integer.parseInt(parts[1]), parts[2], Instant.now(), null,
                        Integer.parseInt(parts[4]), Integer.parseInt(parts[3]));
                    if ("active".equals(st.status()) || "paused".equals(st.status())) {
                        states.put(sid, st);
                        restored++;
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (restored > 0) {
                ensureWatchdog();
                log.info("Restored {} persisted heartbeat(s) after restart", restored);
            }
        } catch (Exception ex) {
            log.warn("Heartbeat restore failed: {}", ex.getMessage());
        }
    }

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
        persist(sessionId, st);
        ensureWatchdog();
        return st;
    }

    public synchronized HeartbeatState pause(UUID sessionId) {
        HeartbeatState st = states.get(sessionId);
        if (st == null || !"active".equals(st.status())) return null;
        st = new HeartbeatState(st.prompt(), st.intervalSeconds(), "paused",
            st.createdAt(), st.lastFiredAt(), st.fireCount(), st.maxTicks());
        states.put(sessionId, st);
        persist(sessionId, st);
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
        boolean removed = states.remove(sessionId) != null;
        if (removed) persist(sessionId, null);
        return removed;
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
        if (watchdogRunning.get()) return;
        if (watchdogRunning.compareAndSet(false, true)) {
            Thread t = new Thread(this::watchdogLoop, "heartbeat-watchdog");
            t.setDaemon(true);
            t.start();
            log.info("Heartbeat watchdog started (poll every {}s)", POLL_SECONDS);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean watchdogRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

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
            deliveryAttempts.put(sessionId, new java.util.concurrent.atomic.AtomicInteger());
        }
        // /loop contract: LOOP_COMPLETE in the response stops the loop.
        if (responseSignalsComplete(response)) {
            log.info("Heartbeat/loop for session {} signalled LOOP_COMPLETE — clearing", sessionId);
            states.remove(sessionId);
            persist(sessionId, null);
        } else if (fired.maxTicks() > 0 && fired.fireCount() >= fired.maxTicks()) {
            log.info("Heartbeat/loop for session {} reached --times cap ({}); clearing", sessionId, fired.maxTicks());
            states.remove(sessionId);
            persist(sessionId, null);
        } else {
            persist(sessionId, fired);   // keep fireCount durable
        }
    }

    /**
     * Peek the last fired result WITHOUT clearing it (Hermes delivery-ledger
     * semantics: a failed send must not lose the message). The bot ACKs via
     * {@link #ackFireResult} after a successful Telegram send.
     */
    public String peekLastFireResult(UUID sessionId) {
        return lastFireResults.get(sessionId);
    }

    /** ACK: drop the delivered result. No-op when nothing is pending. */
    public boolean ackFireResult(UUID sessionId) {
        deliveryAttempts.remove(sessionId);
        return lastFireResults.remove(sessionId) != null;
    }

    /** Count a failed delivery attempt; true when the result should be dropped. */
    public boolean shouldDropUndeliverable(UUID sessionId) {
        var n = deliveryAttempts.get(sessionId);
        if (n == null) return false;
        return n.incrementAndGet() >= 5;
    }

    /** Busy-check seam (package-private for test overrides). */
    boolean isBusy(UUID sessionId) {
        return agentRuntime != null && agentRuntime.isSessionBusy(sessionId);
    }
}
