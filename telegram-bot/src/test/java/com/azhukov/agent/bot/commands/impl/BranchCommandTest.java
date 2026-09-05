package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BranchCommandTest {

    @Test
    void handle_withSession_callsBackendBranch() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.branchSession(anyString(), any())).thenReturn("Branched session: new-123");

        var cmd = new BranchCommand(client);
        UpdateEvent event = makeEvent("");

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(session.getId());

        String result = cmd.handle(event, session);

        assertThat(result).isEqualTo("Branched session: new-123");
        verify(client).branchSession(session.getId().toString(), "");
    }

    @Test
    void handle_noSession_returnsError() {
        AgentBackendClient client = mock(AgentBackendClient.class);

        var cmd = new BranchCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("No active session to branch.");
        verify(client, never()).branchSession(anyString(), any());
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/branch " + args,
            null, null, null, null, null, null, true, "branch", args);
    }
}