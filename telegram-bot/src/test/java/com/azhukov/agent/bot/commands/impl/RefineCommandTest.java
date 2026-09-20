package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /refine} contract (Hermes slash_commands.py parity): on-demand
 * background review of the current conversation. No backend session → early
 * exit; accepted → message from backend; rejected → reason surfaced;
 * unavailable → explicit failure text.
 */
class RefineCommandTest {

    private final AgentBackendClient client = mock(AgentBackendClient.class);
    private final RefineCommand cmd = new RefineCommand(client);
    private final ObjectMapper mapper = new ObjectMapper();

    private UpdateEvent event(String args) {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 1L, 1L, "u",
            "/refine " + args, null, null, null, null, null, null, true, "refine", args);
    }

    private BotSessionEntity session(UUID backendId) {
        BotSessionEntity s = new BotSessionEntity();
        if (backendId != null) {
            s.setBackendSessionId(backendId);
        }
        return s;
    }

    @Test
    void noBackendSession_earlyExit() {
        String out = cmd.handle(event(""), session(null));
        assertThat(out).contains("Nothing to refine yet");
    }

    @Test
    void accepted_returnsBackendMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(client.suggestionPostJson(eq("/api/v1/agent/refine"), any()))
            .thenReturn(mapper.readTree("{\"accepted\":true,\"message\":\"Review started\"}"));

        String out = cmd.handle(event(""), session(id));
        assertThat(out).contains("Review started");
    }

    @Test
    void accepted_defaultMessageWhenBackendOmitsIt() throws Exception {
        UUID id = UUID.randomUUID();
        when(client.suggestionPostJson(anyString(), any()))
            .thenReturn(mapper.readTree("{\"accepted\":true}"));

        String out = cmd.handle(event(""), session(id));
        assertThat(out).contains("Reviewing this conversation in the background");
    }

    @Test
    void rejected_surfacesReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(client.suggestionPostJson(anyString(), any()))
            .thenReturn(mapper.readTree("{\"accepted\":false,\"reason\":\"review already running\"}"));

        String out = cmd.handle(event(""), session(id));
        assertThat(out).contains("failed to start");
        assertThat(out).contains("review already running");
    }

    @Test
    void backendUnavailable_reportsFailure() {
        UUID id = UUID.randomUUID();
        when(client.suggestionPostJson(anyString(), any())).thenReturn(null);

        String out = cmd.handle(event(""), session(id));
        assertThat(out).contains("backend unavailable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void focusArg_isPassedAsBodyField() throws Exception {
        UUID id = UUID.randomUUID();
        when(client.suggestionPostJson(anyString(), any()))
            .thenReturn(mapper.readTree("{\"accepted\":true}"));

        cmd.handle(event("focus on tool usage"), session(id));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).suggestionPostJson(anyString(), captor.capture());
        assertThat(captor.getValue()).containsEntry("sessionId", id.toString())
            .containsEntry("focus", "focus on tool usage");
    }
}
