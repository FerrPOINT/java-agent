package com.azhukov.agent.bot.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.azhukov.agent.bot.core.AgentBackendClient;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background watcher that proactively finalizes expired sessions.
 *
 * <p>Runs on a daemon thread at a fixed interval, checks all active sessions
 * against the configured {@link SessionResetPolicy}, and finalizes (deactivates)
 * expired sessions before the user sends the next message.
 *
 * <p>Mirrors the Python session expiry watcher in {@code gateway/session.py}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionExpiryWatcher {

    private final BotSessionStore sessionStore;
    private final SessionResetPolicy resetPolicy;
    private final AgentBackendClient backendClient;
    // rev-123 Hermes parity (gateway/session.py:1421 _has_active_processes_safe,
    // :2396): sessions with active work are NEVER considered expired. Java
    // equivalent of "active processes": a busy chat (mid-turn) — finalizing a
    // session mid-turn orphans its in-flight results (messages persisted to a
    // deactivated session, backend per-session state wiped under a running
    // agent). Checked via BusySessionHandler.isBusy; check failures also keep
    // the session alive (Hermes fails CLOSED on registry errors).
    private final BusySessionHandler busyHandler;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-expiry-watcher");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean started = false;

    /**
     * Auto-start the watcher when the application is ready.
     * Uses a default check interval of 1 hour (3600 seconds).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoStart() {
        start(3600L);
    }

    /**
     * Start the background watcher. Idempotent — safe to call multiple times.
     *
     * @param checkIntervalSeconds how often to check for expired sessions
     */
    public void start(long checkIntervalSeconds) {
        if (started) return;
        started = true;
        scheduler.scheduleWithFixedDelay(this::checkExpiredSessions,
            checkIntervalSeconds, checkIntervalSeconds, TimeUnit.SECONDS);
        log.info("Session expiry watcher started (interval={}s, mode={}, idleMinutes={}, atHour={})",
            checkIntervalSeconds, resetPolicy.getMode(), resetPolicy.getIdleMinutes(),
            resetPolicy.getAtHour());
    }

    /**
     * Check all active sessions and finalize expired ones.
     */
    void checkExpiredSessions() {
        try {
            Instant now = Instant.now();
            List<BotSessionEntity> activeSessions = sessionStore.listActiveSessions();
            for (BotSessionEntity session : activeSessions) {
                // rev-123: never finalize a session whose chat is mid-turn
                // (Hermes _has_active_processes_safe).
                try {
                    if (busyHandler != null && session.getChatId() != null
                        && busyHandler.isBusy(Long.parseLong(session.getChatId()))) {
                        log.debug("Session {} not expired — chat busy (active turn)", session.getId());
                        continue;
                    }
                } catch (NumberFormatException nfe) {
                    // Non-numeric chat id (e.g. DM topic key) — no busy mapping possible.
                } catch (Exception busyEx) {
                    // Fail CLOSED (Hermes): unknown busy state keeps the session alive.
                    log.warn("Busy check failed for session {}; keeping it alive: {}",
                        session.getId(), busyEx.getMessage());
                    continue;
                }
                String reason = resetPolicy.shouldReset(session.getCreatedAt(), session.getUpdatedAt(), now);
                if (reason != null) {
                    log.info("Finalizing expired session {} (reason={}, userId={})",
                        session.getId(), reason, session.getUserId());
                    // rev-69: notify backend to clean up per-session state
                    // (context engine, guardrails, nudge counters, prompt cache,
                    // interrupt tokens, steer buffer). Without this, expired bot
                    // sessions leak backend state forever — the bot deactivates
                    // its own row but the backend never knows.
                    try {
                        if (session.getBackendSessionId() != null) {
                            backendClient.resetSession(session.getBackendSessionId().toString());
                        }
                    } catch (Exception e) {
                        log.debug("Backend cleanup for expired session {} failed: {}",
                            session.getId(), e.getMessage());
                    }
                    sessionStore.finalizeSession(session.getId());
                }
            }
        } catch (Exception e) {
            log.warn("Session expiry watcher error: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}