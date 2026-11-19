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
    void finalizesExpiredSessionsBeforeRetryingBackloggedCleanup() {
        var expired = new BotSessionEntity();
        expired.setId(UUID.randomUUID());
        expired.setUserId("owner");
        expired.setActive(true);
        expired.setCreatedAt(Instant.now().minusSeconds(3600));
        expired.setUpdatedAt(Instant.now().minusSeconds(3600));
        var pending = new BotSessionEntity();
        pending.setId(UUID.randomUUID());
        when(sessionStore.listActiveSessions()).thenReturn(List.of(expired));
        when(sessionStore.pendingCleanups()).thenReturn(List.of(pending));

        watcher.checkExpiredSessions();

        var order = inOrder(sessionStore);
        order.verify(sessionStore).finalizeWithCleanupPending(expired.getId(), expired.getUpdatedAt());
        order.verify(sessionStore).retryCleanup(eq(pending.getId()), any());
    }

    @Test
    void staleSessionFailureDoesNotPreventOtherExpirationsOrRetries() {
        var bad = new BotSessionEntity();
        bad.setId(UUID.randomUUID()); bad.setUserId("bad");
        bad.setCreatedAt(Instant.now().minusSeconds(3600)); bad.setUpdatedAt(Instant.now().minusSeconds(3600));
        var good = new BotSessionEntity();
        good.setId(UUID.randomUUID()); good.setUserId("good");
        good.setCreatedAt(Instant.now().minusSeconds(3600)); good.setUpdatedAt(Instant.now().minusSeconds(3600));
        var pending = new BotSessionEntity(); pending.setId(UUID.randomUUID());
        when(sessionStore.listActiveSessions()).thenReturn(List.of(bad, good));
        when(sessionStore.pendingCleanups()).thenReturn(List.of(pending));
        when(sessionStore.finalizeWithCleanupPending(eq(bad.getId()), any())).thenThrow(new IllegalStateException("fixture"));

        watcher.checkExpiredSessions();

        verify(sessionStore).finalizeWithCleanupPending(good.getId(), good.getUpdatedAt());
        verify(sessionStore).retryCleanup(eq(pending.getId()), any());
    }

    @Test
    void failedActiveLookupDoesNotPreventPendingCleanup() {
        var pending = new BotSessionEntity();
        pending.setId(UUID.randomUUID());
        when(sessionStore.listActiveSessions()).thenThrow(new IllegalStateException("fixture"));
        when(sessionStore.pendingCleanups()).thenReturn(List.of(pending));

        watcher.checkExpiredSessions();

        verify(sessionStore).retryCleanup(eq(pending.getId()), any());
    }

    @Test
    void failedBacklogQueryDoesNotPreventNewlyFinalizedCleanup() {
        var expired = new BotSessionEntity();
        expired.setId(UUID.randomUUID());
        expired.setUserId("owner");
        expired.setCreatedAt(Instant.now().minusSeconds(3600));
        expired.setUpdatedAt(Instant.now().minusSeconds(3600));
        when(sessionStore.listActiveSessions()).thenReturn(List.of(expired));
        when(sessionStore.pendingCleanups()).thenThrow(new IllegalStateException("fixture"));

        watcher.checkExpiredSessions();

        verify(sessionStore).finalizeWithCleanupPending(expired.getId(), expired.getUpdatedAt());
        verify(sessionStore).retryCleanup(eq(expired.getId()), any());
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
