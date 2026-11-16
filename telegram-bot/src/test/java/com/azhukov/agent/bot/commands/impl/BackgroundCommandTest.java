package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BackgroundCommandTest {

    @Test
    void handle_withPrompt_callsBackend() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.runBackground("do something", null, "456")).thenReturn("session-123");

        var cmd = new BackgroundCommand(client);
        UpdateEvent event = makeEvent("do something");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("session-123");
        verify(client).runBackground("do something", null, "456");
    }

    @Test
    void handle_noPrompt_returnsUsage() {
        AgentBackendClient client = mock(AgentBackendClient.class);

        var cmd = new BackgroundCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("Usage: /background <prompt>");
        verify(client, never()).runBackground(any(), any(), org.mockito.ArgumentMatchers.anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {456L, 987L})
    void backgroundNamesTheActualSenderWithoutASession(long sender) {
        AgentBackendClient client = mock(AgentBackendClient.class);
        String owner = Long.toString(sender);
        when(client.runBackground("work", null, owner)).thenReturn("accepted");
        var event = new UpdateEvent(1, Type.COMMAND, 123, sender, "user", "/background work",
            null, null, null, null, null, null, true, "background", "work");
        assertThat(new BackgroundCommand(client).handle(event, null)).isEqualTo("accepted");
        verify(client).runBackground("work", null, owner);
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/background " + args,
            null, null, null, null, null, null, true, "background", args);
    }
}