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
        when(sessionStore.retryCleanup(any(UUID.class), any())).thenAnswer(invocation ->
            ((java.util.function.Function<UUID, Boolean>) invocation.getArgument(1)).apply(invocation.getArgument(0)));
        when(sessionStore.finalizeWithCleanupPending(any(UUID.class), any())).thenReturn(true);
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

        watcher.checkExpiredSessions();

        verify(backendClient).cleanupSession(sessionId.toString());
        verify(backendClient, never()).resetSession(anyString());
        verify(sessionStore).finalizeWithCleanupPending(sessionId, session.getUpdatedAt());
    }

    @Test
    void failedCleanupIsRetriedAfterSessionBecomesInactive() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(sessionStore.listActiveSessions()).thenReturn(List.of(session), List.of());
        when(sessionStore.pendingCleanups()).thenReturn(List.of(), List.of(session));
        when(backendClient.cleanupSession(sessionId.toString())).thenReturn(false, true);
        watcher.checkExpiredSessions();
        watcher.checkExpiredSessions();
        verify(sessionStore).finalizeWithCleanupPending(sessionId, session.getUpdatedAt());
        verify(backendClient, times(2)).cleanupSession(sessionId.toString());
        verify(backendClient, never()).resetSession(anyString());
    }

    @Test
    void falseCleanupResultStillFinalizesAndWillNotBeRetriedAsActiveSession() {
        UUID sessionId = UUID.randomUUID();
        BotSessionEntity session = new BotSessionEntity();
        session.setId(sessionId);
        session.setBackendSessionId(sessionId);
        session.setUserId("user-1");
        session.setActive(true);
        session.setCreatedAt(Instant.now().minusSeconds(3600));
        session.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(sessionStore.listActiveSessions()).thenReturn(List.of(session), List.of());
        when(backendClient.cleanupSession(sessionId.toString())).thenReturn(false);
        watcher.checkExpiredSessions();
        watcher.checkExpiredSessions();
        verify(sessionStore).finalizeWithCleanupPending(sessionId, session.getUpdatedAt());
        verify(backendClient, times(1)).cleanupSession(sessionId.toString());
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
        verify(sessionStore).finalizeWithCleanupPending(sessionId, session.getUpdatedAt());
    }
}
