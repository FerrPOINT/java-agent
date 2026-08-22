package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * /save end-to-end semantics: history fetch → render → document send.
 */
class SaveCommandTest {

    private AgentBackendClient backend;
    private TelegramClient telegram;
    private SaveCommand cmd;
    private final ObjectMapper mapper = new ObjectMapper();
    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        backend = mock(AgentBackendClient.class);
        telegram = mock(TelegramClient.class);
        cmd = new SaveCommand(backend, telegram);
        session = mock(BotSessionEntity.class);
        UUID sid = UUID.randomUUID();
        when(session.getBackendSessionId()).thenReturn(sid);
    }

    private UpdateEvent args(String a) {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 123, 456, "user", "/save", null, null, null, null, null, null, true, "save", a == null ? "" : a);
    }

    @Test
    void noSessionMeansNothingToSave() {
        when(session.getBackendSessionId()).thenReturn(null);
        assertThat(cmd.handle(args(""), session)).contains("Nothing to save");
    }

    @Test
    void emptyHistoryMeansNothingToSave() {
        when(backend.suggestionGet(startsWith("/api/v1/agent/session/"))).thenReturn(mapper.createArrayNode());
        assertThat(cmd.handle(args(""), session)).contains("Nothing to save");
    }

    @Test
    void invalidFormatShowsUsage() {
        assertThat(cmd.handle(args("pdf"), session)).contains("Usage");
    }

    @Test
    void mdExportSendsDocument() {
        ArrayNode history = mapper.createArrayNode();
        ObjectNode u = mapper.createObjectNode();
        u.put("role", "user"); u.put("content", "hello");
        ObjectNode a = mapper.createObjectNode();
        a.put("role", "assistant"); a.put("content", "hi there");
        history.add(u); history.add(a);
        when(backend.suggestionGet(startsWith("/api/v1/agent/session/"))).thenReturn(history);
        when(telegram.sendDocument(eq(123L), any(byte[].class), contains(".md"), any(), any()))
            .thenReturn(Optional.of(55L));

        String result = cmd.handle(args(""), session);

        assertThat(result).isNull(); // document sent, no extra text
        verify(telegram).sendDocument(eq(123L), any(byte[].class), argThat(
            (String n) -> n.endsWith(".md")), any(), any());
    }

    @Test
    void jsonExportSendsJsonDocument() {
        ArrayNode history = mapper.createArrayNode();
        ObjectNode u = mapper.createObjectNode();
        u.put("role", "user"); u.put("content", "x");
        history.add(u);
        when(backend.suggestionGet(startsWith("/api/v1/agent/session/"))).thenReturn(history);
        when(telegram.sendDocument(eq(123L), any(byte[].class), contains(".json"), any(), any()))
            .thenReturn(Optional.of(56L));

        cmd.handle(args("json"), session);

        verify(telegram).sendDocument(eq(123L), any(byte[].class), argThat(
            (String n) -> n.endsWith(".json")), any(), any());
    }

    @Test
    void sendFailureReportsError() {
        ArrayNode history = mapper.createArrayNode();
        ObjectNode u = mapper.createObjectNode();
        u.put("role", "user"); u.put("content", "x");
        history.add(u);
        when(backend.suggestionGet(startsWith("/api/v1/agent/session/"))).thenReturn(history);
        when(telegram.sendDocument(anyLong(), any(byte[].class), any(), any(), any()))
            .thenReturn(Optional.empty());

        assertThat(cmd.handle(args(""), session)).contains("Failed");
    }
}
