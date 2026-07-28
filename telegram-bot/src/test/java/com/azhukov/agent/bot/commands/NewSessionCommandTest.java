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
        UpdateEvent event = new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/new", null, null, null, null, null, null, true, "new", "");
        String result = cmd.handle(event, session);
        assertThat(result).contains("cleared");
    }
}