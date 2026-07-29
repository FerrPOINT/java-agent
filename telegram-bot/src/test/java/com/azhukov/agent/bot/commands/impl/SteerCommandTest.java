package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SteerCommandTest {

    private AgentBackendClient backendClient;
    private SteerCommand cmd;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new SteerCommand(backendClient);
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("steer");
        assertThat(cmd.description()).isEqualTo("Inject a mid-run note to the agent");
    }

    @Test
    void noArgsShowsUsage() {
        String result = cmd.handle(textEvent("/steer", ""), null);
        assertThat(result).contains("Usage:");
        assertThat(result).contains("/steer");
    }

    @Test
    void noSessionReturnsError() {
        String result = cmd.handle(textEvent("/steer", "focus on auth"), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void acceptedReturnsConfirmation() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(backendClient.steer(anyString(), anyString())).thenReturn(true);

        String result = cmd.handle(textEvent("/steer", "focus on auth"), session);

        assertThat(result).contains("injected");
        assertThat(result).contains("focus on auth");
    }

    @Test
    void rejectedReturnsError() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(backendClient.steer(anyString(), anyString())).thenReturn(false);

        String result = cmd.handle(textEvent("/steer", "focus on auth"), session);

        assertThat(result).contains("Failed");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "steer", args);
    }
}