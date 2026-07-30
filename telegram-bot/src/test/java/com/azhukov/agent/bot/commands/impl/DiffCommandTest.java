package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DiffCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new DiffCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("diff");
        assertThat(cmd.description()).isEqualTo("Show git changes in working directory");
    }

    @Test
    void noArgs_runsPlainGitDiff() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(eq("Run: git diff and show me the output"), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("diff output"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).isEqualTo("diff output");
    }

    @Test
    void stagedArgs_runsStagedDiff() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(eq("Run: git diff --staged and show me the output"), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("staged diff"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("staged"), session);
        assertThat(result).isEqualTo("staged diff");
    }

    @Test
    void allArgs_runsHeadDiff() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(eq("Run: git diff HEAD and show me the output"), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("head diff"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("all"), session);
        assertThat(result).isEqualTo("head diff");
    }

    @Test
    void statArgs_runsStatDiff() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(eq("Run: git diff --stat and show me the output"), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("stat diff"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent("stat"), session);
        assertThat(result).isEqualTo("stat diff");
    }

    @Test
    void backendThrows_returnsErrorMessage() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(anyString(), eq(sessionId.toString()))).thenThrow(new RuntimeException("Connection refused"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).contains("Error running git diff");
        assertThat(result).contains("Connection refused");
    }

    @Test
    void backendReturnsErrorContent_passesThrough() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        UUID sessionId = UUID.randomUUID();
        when(client.chat(anyString(), eq(sessionId.toString())))
            .thenReturn(new AgentBackendClient.ChatResult("Error: git not found"));
        var cmd = new DiffCommand(client);
        BotSessionEntity session = newSession(sessionId);
        String result = cmd.handle(makeEvent(""), session);
        assertThat(result).isEqualTo("Error: git not found");
    }

    private BotSessionEntity newSession(UUID id) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(id);
        return session;
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/diff " + args, null, null, null, null, null, null, true, "diff", args);
    }
}