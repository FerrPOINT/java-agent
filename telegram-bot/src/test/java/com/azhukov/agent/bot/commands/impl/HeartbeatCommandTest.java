package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.cron.HeartbeatDeliveryPoller;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
 * {@code /heartbeat} contract (Hermes parity): interval token parsing
 * (Nh/Nm/Ns forms), set/status/pause/resume/clear subcommands, minimum
 * interval 60s, prompt requirement, and delivery-poller wiring on success.
 */
class HeartbeatCommandTest {

    private final AgentBackendClient client = mock(AgentBackendClient.class);
    private final HeartbeatDeliveryPoller poller = mock(HeartbeatDeliveryPoller.class);
    private final HeartbeatCommand cmd = new HeartbeatCommand(client, poller);
    private final ObjectMapper mapper = new ObjectMapper();

    private BotSessionEntity session(UUID id) {
        BotSessionEntity s = new BotSessionEntity();
        s.setId(id);
        s.setBackendSessionId(id);
        return s;
    }

    private UpdateEvent event(String args) {
        return new UpdateEvent(1, UpdateEvent.Type.COMMAND, 7L, 1L, "u",
            "/heartbeat " + args, null, null, null, null, null, null, true, "heartbeat", args);
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @ParameterizedTest
    @CsvSource({"10m,600", "2h,7200", "90s,90", "1h30m,5400", "1h30m10s,5410"})
    void parseIntervalToken_acceptsCompoundForms(String token, int expectedSeconds) {
        assertThat(HeartbeatCommand.parseIntervalToken(token)).isEqualTo(expectedSeconds);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "10x", "m10", "0s", "-5m", "1.5h", "45"})
    void parseIntervalToken_rejectsGarbage(String token) {
        // bare digits without a unit suffix are NOT intervals — they stay part
        // of the prompt ("45 reports" must not become a 45-second heartbeat)
        assertThat(HeartbeatCommand.parseIntervalToken(token)).isNull();
    }

    @Test
    void set_withEveryForm_postsHeartbeatAndWatchesPoller() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPostJson(eq("/api/v1/agent/cron/heartbeat"), any()))
            .thenReturn(json("{\"ok\":true,\"message\":\"Heartbeat set (every 10m)\"}"));

        String out = cmd.handle(event("every 10m Check CI"), session(sid));

        assertThat(out).contains("Heartbeat set");
        assertThat(out).contains("idle");
        verify(poller).watch(sid, 7L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void set_bareIntervalForm_stripsTokenFromPrompt() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPostJson(anyString(), any()))
            .thenReturn(json("{\"ok\":true,\"message\":\"ok\"}"));

        cmd.handle(event("10m check the build"), session(sid));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client).suggestionPostJson(anyString(), captor.capture());
        assertThat(captor.getValue())
            .containsEntry("prompt", "check the build")
            .containsEntry("intervalSeconds", 600);
    }

    @Test
    void set_intervalBelow60s_rejected() {
        String out = cmd.handle(event("every 30s poll"), session(UUID.randomUUID()));
        assertThat(out).contains("minimum is 60s");
    }

    @Test
    void set_missingPrompt_showsPromptRequirement() {
        String out = cmd.handle(event("every 10m"), session(UUID.randomUUID()));
        assertThat(out).contains("prompt is required");
    }

    @Test
    void set_unparsableInterval_showsUsage() {
        String out = cmd.handle(event("do stuff"), session(UUID.randomUUID()));
        assertThat(out).contains("Usage: /heartbeat every");
    }

    @Test
    void set_backendRejects_surfacesReason() throws Exception {
        when(client.suggestionPostJson(anyString(), any()))
            .thenReturn(json("{\"ok\":false,\"reason\":\"heartbeat already active\"}"));

        String out = cmd.handle(event("every 5m x"), session(UUID.randomUUID()));
        assertThat(out).contains("Invalid heartbeat");
        assertThat(out).contains("already active");
    }

    @Test
    void set_backendUnavailable_reportsIt() {
        when(client.suggestionPostJson(anyString(), any())).thenReturn(null);

        String out = cmd.handle(event("every 5m x"), session(UUID.randomUUID()));
        assertThat(out).contains("backend unavailable");
    }

    @Test
    void status_whenSet_reportsHeartbeat() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid))
            .thenReturn(json("{\"set\":true,\"interval\":\"10m\",\"prompt\":\"Check CI\",\"status\":\"active\",\"fireCount\":3}"));

        String out = cmd.handle(event("status"), session(sid));
        assertThat(out).contains("♥ Heartbeat (every 10m)");
        assertThat(out).contains("fired 3×");
    }

    @Test
    void status_notSet_showsUsage() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionGet(anyString())).thenReturn(json("{\"set\":false}"));

        String out = cmd.handle(event(""), session(sid));
        assertThat(out).contains("No heartbeat set.");
    }

    @Test
    void pause_reportsPausedMessage() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/pause"))
            .thenReturn(json("{\"ok\":true,\"message\":\"Heartbeat paused: Check CI\"}"));

        String out = cmd.handle(event("pause"), session(sid));
        assertThat(out).contains("Heartbeat paused");
    }

    @Test
    void resume_reportsResumedMessage() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/resume"))
            .thenReturn(json("{\"ok\":true,\"message\":\"Heartbeat resumed (every 10m)\"}"));

        String out = cmd.handle(event("resume"), session(sid));
        assertThat(out).contains("Heartbeat resumed");
    }

    @Test
    void clearAndStopAndOff_allMapToClearEndpoint() throws Exception {
        UUID sid = UUID.randomUUID();
        when(client.suggestionPost(anyString()))
            .thenReturn(json("{\"ok\":true}"));

        for (String sub : new String[]{"clear", "stop", "off"}) {
            String out = cmd.handle(event(sub), session(sid));
            assertThat(out).contains("Heartbeat cleared.");
        }
        verify(client, org.mockito.Mockito.times(3))
            .suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/clear");
    }
}
