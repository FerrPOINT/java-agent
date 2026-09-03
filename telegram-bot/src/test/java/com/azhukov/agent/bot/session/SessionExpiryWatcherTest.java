package com.azhukov.agent.bot.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionExpiryWatcherTest {

    private BotSessionStore sessionStore;
    private SessionResetPolicy resetPolicy;
    private SessionExpiryWatcher watcher;

    @BeforeEach
    void setUp() {
        sessionStore = mock(BotSessionStore.class);
        resetPolicy = new SessionResetPolicy();
        resetPolicy.setMode(SessionResetMode.IDLE);
        resetPolicy.setIdleMinutes(1);
        com.azhukov.agent.bot.core.AgentBackendClient backendClient = mock(
            com.azhukov.agent.bot.core.AgentBackendClient.class);
        watcher = new SessionExpiryWatcher(sessionStore, resetPolicy, backendClient);
    }

    @AfterEach
    void tearDown() {
        watcher.stop();
    }

    @Test
    void checkExpiredSessionsFinalizesExpiredOnes() {
        // Create an expired session
        BotSessionEntity expiredSession = new BotSessionEntity();
        expiredSession.setId(UUID.randomUUID());
        expiredSession.setUserId("user-1");
        expiredSession.setCreatedAt(Instant.now().minusSeconds(3600));
        expiredSession.setUpdatedAt(Instant.now().minusSeconds(3600));
        expiredSession.setActive(true);

        // Create a fresh session
        BotSessionEntity freshSession = new BotSessionEntity();
        freshSession.setId(UUID.randomUUID());
        freshSession.setUserId("user-2");
        freshSession.setCreatedAt(Instant.now());
        freshSession.setUpdatedAt(Instant.now());
        freshSession.setActive(true);

        when(sessionStore.listActiveSessions()).thenReturn(List.of(expiredSession, freshSession));

        watcher.checkExpiredSessions();

        // Only the expired session should be finalized
        verify(sessionStore).finalizeSession(expiredSession.getId());
        verify(sessionStore, never()).finalizeSession(freshSession.getId());
    }

    @Test
    void checkExpiredSessionsWithNoActiveSessions() {
        when(sessionStore.listActiveSessions()).thenReturn(List.of());

        watcher.checkExpiredSessions();

        verify(sessionStore, never()).finalizeSession(any());
    }

    @Test
    void checkExpiredSessionsWithNoneModeDoesNothing() {
        resetPolicy.setMode(SessionResetMode.NONE);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setCreatedAt(Instant.now().minusSeconds(999999));
        session.setUpdatedAt(Instant.now().minusSeconds(999999));
        session.setActive(true);

        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));

        watcher.checkExpiredSessions();

        verify(sessionStore, never()).finalizeSession(any());
    }

    @Test
    void startIsIdempotent() {
        watcher.start(3600);
        watcher.start(3600); // Should not throw
    }
}