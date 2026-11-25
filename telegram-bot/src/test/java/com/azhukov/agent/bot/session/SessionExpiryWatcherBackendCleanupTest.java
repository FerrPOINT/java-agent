package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.core.SessionApiClient.CleanupResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Verify that expiry releases backend runtime state without resetting the transcript.
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
        watcher = new SessionExpiryWatcher(sessionStore, resetPolicy, backendClient,
            mock(com.azhukov.agent.bot.session.BusySessionHandler.class));
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
        session.setBackendSessionId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));
        when(backendClient.cleanupSession(sessionId.toString())).thenReturn(CleanupResult.CLEANED);

        watcher.checkExpiredSessions();

        verify(backendClient).cleanupSession(sessionId.toString());
        verify(backendClient, never()).resetSession(anyString());
        verify(sessionStore).finalizeSession(sessionId);
    }

    @Test
    void failedCleanupStillFinalizesAndWillNotBeRetriedAsActiveSession() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(sessionStore.listActiveSessions()).thenReturn(List.of(session), List.of());
        when(backendClient.cleanupSession(sessionId.toString())).thenReturn(CleanupResult.FAILED);
        watcher.checkExpiredSessions();
        watcher.checkExpiredSessions();
        verify(sessionStore).finalizeSession(sessionId);
        verify(backendClient, times(1)).cleanupSession(sessionId.toString());
    }

    @Test
    void backendBusyDefersExpiryUntilTheNextNormalCheck() {
        var session = new BotSessionEntity();
        var id = UUID.randomUUID();
        session.setId(id);
        session.setBackendSessionId(id);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(session.getCreatedAt());
        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));
        when(backendClient.cleanupSession(id.toString()))
            .thenReturn(CleanupResult.BUSY, CleanupResult.CLEANED);

        watcher.checkExpiredSessions();
        verify(sessionStore, never()).finalizeSession(id);

        watcher.checkExpiredSessions();
        verify(sessionStore).finalizeSession(id);
        verify(backendClient, times(2)).cleanupSession(id.toString());
        verify(backendClient, never()).resetSession(anyString());
    }

    @Test
    void newActivityAfterBusyCancelsExpiry() {
        var session = new BotSessionEntity();
        var id = UUID.randomUUID();
        session.setId(id);
        session.setBackendSessionId(id);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(session.getCreatedAt());
        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));
        when(backendClient.cleanupSession(id.toString())).thenReturn(CleanupResult.BUSY);

        watcher.checkExpiredSessions();
        session.setUpdatedAt(Instant.now());
        watcher.checkExpiredSessions();

        verify(backendClient).cleanupSession(id.toString());
        verify(sessionStore, never()).finalizeSession(id);
    }

    @Test
    void backendCleanupFailureDoesNotBlockFinalize() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));

        when(sessionStore.listActiveSessions()).thenReturn(List.of(session));
        doThrow(new RuntimeException("backend down")).when(backendClient).cleanupSession(anyString());

        watcher.checkExpiredSessions();

        verify(backendClient).cleanupSession(sessionId.toString());
        verify(backendClient, never()).resetSession(anyString());
        // finalize must still happen even if backend cleanup failed
        verify(sessionStore).finalizeSession(sessionId);
    }
}
