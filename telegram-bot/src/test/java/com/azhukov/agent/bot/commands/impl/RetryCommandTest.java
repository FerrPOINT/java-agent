package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotMessageEntity;
import com.azhukov.agent.bot.session.BotMessageRepository;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetryCommandTest {

    @Test
    void retriesLastUserMessage() {
        BotMessageRepository repo = mock(BotMessageRepository.class);
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        BotMessageEntity userMsg = new BotMessageEntity();
        userMsg.setRole("user");
        userMsg.setContent("Hello");
        BotMessageEntity assistantMsg = new BotMessageEntity();
        assistantMsg.setRole("assistant");
        assistantMsg.setContent("Hi there");
        when(repo.findBySessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of(assistantMsg, userMsg));
        when(client.chat("Hello", sessionId.toString())).thenReturn(new AgentBackendClient.ChatResult("Hi again"));
        var cmd = new RetryCommand(repo, client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).isEqualTo("Hi again");
        verify(client).undoTurns(sessionId.toString(), 1);
    }

    @Test
    void noUserMessage_returnsNotFound() {
        BotMessageRepository repo = mock(BotMessageRepository.class);
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(repo.findBySessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of());
        var cmd = new RetryCommand(repo, client);
        BotSessionEntity session = newSession(sessionId);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("No previous user message");
        verifyNoInteractions(client);
    }

    @Test
    void nullSession_returnsNoActiveSession() {
        var cmd = new RetryCommand(mock(BotMessageRepository.class), mock(AgentBackendClient.class));
        String result = cmd.handle(makeEvent(""), null);
        assertThat(result).contains("No active session");
    }

    @Test
    void nameAndDescription() {
        var cmd = new RetryCommand(mock(BotMessageRepository.class), mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("retry");
        assertThat(cmd.description()).isEqualTo("Retry last message");
    }

    @Test
    void undoTurnsCalledBeforeChat() {
        BotMessageRepository repo = mock(BotMessageRepository.class);
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        BotMessageEntity userMsg = new BotMessageEntity();
        userMsg.setRole("user");
        userMsg.setContent("Test message");
        when(repo.findBySessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of(userMsg));
        when(client.chat(anyString(), anyString())).thenReturn(new AgentBackendClient.ChatResult("Response"));
        var cmd = new RetryCommand(repo, client);
        BotSessionEntity session = newSession(sessionId);
        cmd.handle(makeEvent(""), session);
        // Verify undoTurns was called with the session ID and 1 turn
        verify(client).undoTurns(sessionId.toString(), 1);
        verify(client).chat("Test message", sessionId.toString());
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/retry " + args, null, null, null, null, null, null, true, "retry", args);
    }
}