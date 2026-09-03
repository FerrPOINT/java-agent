package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.core.AgentBackendClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * rev-69: verify that SessionExpiryWatcher calls backendClient.resetSession
 * when finalizing an expired session — without this, backend per-session
 * state (context engine, guardrails, nudge counters, prompt cache) leaks.
 */
class SessionExpiryWatcherBackendCleanupTest {

    private BotSessionStore sessionStore;
    private SessionResetPolicy resetPolicy;
    private AgentBackendClient backendClient;
    private SessionExpiryWatcher watcher;

    @BeforeEach
    void setUp() {
        sessionStore = mock(BotSessionStore.class);
        resetPolicy = new SessionResetPolicy();
        resetPolicy.setMode(SessionResetMode.IDLE);
        resetPolicy.setIdleMinutes(1);
        backendClient = mock(AgentBackendClient.class);
        watcher = new SessionExpiryWatcher(sessionStore, resetPolicy, backendClient);
    }

    @AfterEach
    void tearDown() {
        watcher.stop();
    }

    @Test
    void expiredSessionTriggersBackendCleanup() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));

        watcher.checkExpiredSessions();

        verify(backendClient).resetSession(sessionId.toString());
        verify(sessionStore).finalizeSession(sessionId);
    }

    @Test
    void backendCleanupFailureDoesNotBlockFinalize() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));
        when(backendClient.resetSession(anyString())).thenThrow(new RuntimeException("backend down"));

        watcher.checkExpiredSessions();

        // finalize must still happen even if backend cleanup failed
        verify(sessionStore).finalizeSession(sessionId);
    }
}
