package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetryCommandTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retriesLastUserMessageFromBackendHistory() throws Exception {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        JsonNode history = mapper.readTree("""
            [{"role":"user","content":"Hello"},
             {"role":"assistant","content":"Hi there"},
             {"role":"user","content":"Second question"}]
            """);
        when(client.getMessages(sessionId.toString(), 50)).thenReturn(history);
        when(client.chat("Second question", sessionId.toString()))
            .thenReturn(new AgentBackendClient.ChatResult("Hi again"));
        var cmd = new RetryCommand(client);
        String result = cmd.handle(makeEvent(""), newSession(sessionId));
        assertThat(result).isEqualTo("Hi again");
        verify(client).undoTurns(sessionId.toString(), 1);
        verify(client).chat("Second question", sessionId.toString());
    }

    @Test
    void noUserMessage_returnsNotFound() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.getMessages(sessionId.toString(), 50)).thenReturn(null);
        var cmd = new RetryCommand(client);
        String result = cmd.handle(makeEvent(""), newSession(sessionId));
        assertThat(result).contains("No previous user message");
        verify(client, never()).chat(any(), any());
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new RetryCommand(mock(AgentBackendClient.class));
        assertThat(cmd.handle(makeEvent(""), null)).contains("No active session");
    }

    @Test
    void nameAndDescription() {
        var cmd = new RetryCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("retry");
        assertThat(cmd.description()).isEqualTo("Retry last message");
    }

    private BotSessionEntity newSession(UUID backendId) {
        BotSessionEntity s = new BotSessionEntity();
        s.setBackendSessionId(backendId);
        return s;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123L, 456L, "user", "/retry " + args,
            null, null, null, null, null, null, true, "retry", args);
    }
}
