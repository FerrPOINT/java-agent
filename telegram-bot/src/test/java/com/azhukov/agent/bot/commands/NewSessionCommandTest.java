package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.NewSessionCommand;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewSessionCommandTest {

    @Test
    void newSessionNeverDeletesPreviousBackendHistory() {
        var store = mock(BotSessionStore.class);
        var backend = mock(AgentBackendClient.class);
        var session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        session.setUserId("owner");
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        when(store.retryCleanup(org.mockito.ArgumentMatchers.eq(session.getId()), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((java.util.function.Function<UUID, Boolean>) invocation.getArgument(1)).apply(session.getBackendSessionId()));
        new NewSessionCommand(store, backend).handle(null, session);
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(anyString());
        org.mockito.Mockito.verify(store).finalizeWithCleanupPending(session.getId(), session.getUpdatedAt());
    }

    @Test
    void successCleanupStartsFreshAndNeverCallsReset() {
        var store = mock(BotSessionStore.class);
        var backend = mock(AgentBackendClient.class);
        var session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        session.setUserId("owner");
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        when(store.retryCleanup(org.mockito.ArgumentMatchers.eq(session.getId()), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((java.util.function.Function<UUID, Boolean>) invocation.getArgument(1)).apply(session.getBackendSessionId()));
        when(backend.cleanupSession(session.getBackendSessionId().toString())).thenReturn(true);
        assertThat(new NewSessionCommand(store, backend).handle(null, session)).contains("cleared");
        org.mockito.Mockito.verify(backend).cleanupSession(session.getBackendSessionId().toString());
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(anyString());
        org.mockito.Mockito.verify(store).finalizeWithCleanupPending(session.getId(), session.getUpdatedAt());
    }

    @Test
    void failedNewCleanupMustNotSilentlyDiscardRetry() {
        var store = mock(BotSessionStore.class);
        var backend = mock(AgentBackendClient.class);
        var session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        session.setUserId("owner");
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        when(store.retryCleanup(org.mockito.ArgumentMatchers.eq(session.getId()), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((java.util.function.Function<UUID, Boolean>) invocation.getArgument(1)).apply(session.getBackendSessionId()));
        when(backend.cleanupSession(anyString())).thenReturn(false);
        String result = new NewSessionCommand(store, backend).handle(null, session);
        assertThat(result).contains("cleanup failed");
        org.mockito.Mockito.verify(store).finalizeWithCleanupPending(session.getId(), session.getUpdatedAt());
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(anyString());
    }

    @Test
    void thrownCleanupFailureStillDeactivatesAndReportsFailure() {
        var store = mock(BotSessionStore.class);
        var backend = mock(AgentBackendClient.class);
        var session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        session.setUserId("owner");
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        when(store.retryCleanup(org.mockito.ArgumentMatchers.eq(session.getId()), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((java.util.function.Function<UUID, Boolean>) invocation.getArgument(1)).apply(session.getBackendSessionId()));
        when(backend.cleanupSession(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThat(new NewSessionCommand(store, backend).handle(null, session)).contains("cleanup failed");
        org.mockito.Mockito.verify(store).finalizeWithCleanupPending(session.getId(), session.getUpdatedAt());
        org.mockito.Mockito.verify(backend, org.mockito.Mockito.never()).resetSession(anyString());
    }

    @Test
    void newOnlyFinalizesTheCurrentSession() {
        var store = mock(BotSessionStore.class);
        var session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId("owner");
        session.setThreadId(42L);
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        new NewSessionCommand(store, mock(AgentBackendClient.class)).handle(null, session);
        org.mockito.Mockito.verify(store).finalizeWithCleanupPending(session.getId(), session.getUpdatedAt());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).deactivateAllWithCleanupPending(anyString());
    }

    @Test
    void name_isNew() {
        var cmd = new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("new");
    }

    @Test
    void description_isNotBlank() {
        var cmd = new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class));
        assertThat(cmd.description()).isNotBlank();
    }

    @Test
    void handle_returnsStartMessage() {
        BotSessionStore store = mock(BotSessionStore.class);
        AgentBackendClient backendClient = mock(AgentBackendClient.class);
        when(backendClient.resetSession(anyString())).thenReturn(true);
        var cmd = new NewSessionCommand(store, backendClient);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(store.finalizeWithCleanupPending(session.getId(), session.getUpdatedAt())).thenReturn(true);
        UpdateEvent event = new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/new", null, null, null, null, null, null, true, "new", "");
        String result = cmd.handle(event, session);
        assertThat(result).contains("cleared");
    }
}