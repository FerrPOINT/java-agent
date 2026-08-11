package com.azhukov.agent.bot.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
                String reason = resetPolicy.shouldReset(session.getCreatedAt(), session.getUpdatedAt(), now);
                if (reason != null) {
                    log.info("Finalizing expired session {} (reason={}, userId={})",
                        session.getId(), reason, session.getUserId());
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