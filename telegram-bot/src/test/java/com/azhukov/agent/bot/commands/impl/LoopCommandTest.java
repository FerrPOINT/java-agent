package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.cron.HeartbeatDeliveryPoller;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /loop} contract (Hermes loops.py parity): arg parsing
 * ([interval] prompt [--times N] [--until cond]), status/pause/resume/stop
 * subcommands, LOOP_COMPLETE marker in the wakeup prompt, backend heartbeat
 * calls, and delivery-poller watch/unwatch wiring.
 */
class LoopCommandTest {

    private final AgentBackendClient client = mock(AgentBackendClient.class);
    private final HeartbeatDeliveryPoller poller = mock(HeartbeatDeliveryPoller.class);
    private final LoopCommand cmd = new LoopCommand(client, poller);
    private final ObjectMapper mapper = new ObjectMapper();

    private BotSessionEntity sessionWithBackend(UUID id) {
        BotSessionEntity s = new BotSessionEntity();
        s.setId(id);
        s.setBackendSessionId(id);
        return s;
    }

    private UpdateEvent event(String args) {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 42L, 100L, "user",
            "/loop " + args, null, null, null, null, null, null, true, "loop", args);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void setLoop_parsesIntervalPromptAndWatchesPoller() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPostJson(eq("/api/v1/agent/cron/heartbeat"), any()))
            .thenReturn(json("{\"ok\":true}"));

        String out = cmd.handle(event("10m check service X --times 3"),
            sessionWithBackend(sid));

        assertThat(out).contains("Loop set");
        assertThat(out).contains("600s"); // 10m clamped from below by 60
        assertThat(out).contains("Max 3 iterations");
        verify(poller).watch(eq(sid), eq(42L));
    }

    @Test
    void setLoop_promptCarriesLoopCompleteMarker() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPostJson(eq("/api/v1/agent/cron/heartbeat"), any()))
            .thenReturn(json("{\"ok\":true}"));

        cmd.handle(event("5m do the thing"), sessionWithBackend(sid));

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(client).suggestionPostJson(anyString(), captor.capture());
        String prompt = (String) captor.getValue().get("prompt");
        assertThat(prompt).contains("[/loop]");
        assertThat(prompt).contains("Recurring task: do the thing");
        assertThat(prompt).contains(LoopCommand.LOOP_COMPLETE_MARKER);
        assertThat(captor.getValue().get("intervalSeconds")).isEqualTo(300);
    }

    @Test
    void setLoop_withUntilCondition_stripsItFromPrompt() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPostJson(eq("/api/v1/agent/cron/heartbeat"), any()))
            .thenReturn(json("{\"ok\":true}"));

        cmd.handle(event("poll build --until build is green"), sessionWithBackend(sid));

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(client).suggestionPostJson(anyString(), captor.capture());
        String prompt = (String) captor.getValue().get("prompt");
        assertThat(prompt).contains("Stop condition: build is green");
        assertThat(prompt).doesNotContain("--until");
    }

    @Test
    void setLoop_noPromptLeft_showsUsage() {
        String out = cmd.handle(event("10m"), sessionWithBackend(UUID.randomUUID()));
        assertThat(out).contains("Usage: /loop");
    }

    @Test
    void setLoop_backendRejects_reportsFailure() throws Exception {
        when(client.suggestionPostJson(eq("/api/v1/agent/cron/heartbeat"), any()))
            .thenReturn(json("{\"ok\":false,\"reason\":\"session busy\"}"));

        String out = cmd.handle(event("5m x"), sessionWithBackend(UUID.randomUUID()));
        assertThat(out).contains("/loop failed");
        assertThat(out).contains("session busy");
    }

    @Test
    void status_whenSet_reportsLoop() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid))
            .thenReturn(json("{\"set\":true,\"interval\":\"5m\",\"prompt\":\"check X\",\"status\":\"active\",\"fireCount\":2}"));

        String out = cmd.handle(event("status"), sessionWithBackend(sid));
        assertThat(out).contains("Loop (every 5m)");
        assertThat(out).contains("fired 2×");
    }

    @Test
    void status_notSet_showsUsage() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid))
            .thenReturn(json("{\"set\":false}"));

        String out = cmd.handle(event(""), sessionWithBackend(sid));
        assertThat(out).contains("No loop set.");
    }

    @Test
    void stop_clearsHeartbeatAndUnwatchesPoller() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/clear"))
            .thenReturn(json("{\"ok\":true,\"message\":\"cleared\"}"));

        String out = cmd.handle(event("stop"), sessionWithBackend(sid));
        assertThat(out).contains("cleared");
        verify(poller).unwatch(sid);
    }

    @Test
    void pauseAndResume_callRespectiveEndpoints() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPost(anyString())).thenReturn(json("{\"ok\":true,\"message\":\"paused\"}"));

        cmd.handle(event("pause"), sessionWithBackend(sid));
        verify(client).suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/pause");

        cmd.handle(event("resume"), sessionWithBackend(sid));
        verify(client).suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/resume");
    }
}
