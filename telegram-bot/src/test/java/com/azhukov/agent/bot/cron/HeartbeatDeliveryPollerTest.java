package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Heartbeat result delivery: each fired tick's reply must reach the chat
 * that set the heartbeat (Hermes: gateway wakeup watcher forwards replies).
 */
class HeartbeatDeliveryPollerTest {

    private AgentBackendClient backend;
    private TelegramClient telegram;
    private BotProperties props;
    private HeartbeatDeliveryPoller poller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        backend = mock(AgentBackendClient.class);
        telegram = mock(TelegramClient.class);
        props = mock(BotProperties.class);
        when(props.isCronDeliveryEnabled()).thenReturn(true);
        poller = new HeartbeatDeliveryPoller(backend, telegram, props);
    }

    @Test
    void deliversFiredResultToWatchingChat() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 754334329L);

        ObjectNode result = mapper.createObjectNode();
        result.put("hasResult", true);
        result.put("result", "Nothing has changed — deployment stable.");
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid + "/result")).thenReturn(result);

        ObjectNode status = mapper.createObjectNode();
        status.put("set", true);
        status.put("status", "active");
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid)).thenReturn(status);

        poller.poll();

        verify(telegram).sendMessage(eq(754334329L), contains("Nothing has changed"));
    }

    @Test
    void clearsWatchWhenHeartbeatGone() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 1L);

        ObjectNode noResult = mapper.createObjectNode();
        noResult.put("hasResult", false);
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid + "/result")).thenReturn(noResult);
        ObjectNode noHeartbeat = mapper.createObjectNode();
        noHeartbeat.put("set", false);
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid)).thenReturn(noHeartbeat);

        poller.poll();

        // unwatched — no further polling of this session
        poller.poll();
        verify(backend, times(2)).suggestionGet(anyString());
    }

    @Test
    void truncatesVeryLongResults() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 1L);

        ObjectNode result = mapper.createObjectNode();
        result.put("hasResult", true);
        result.put("result", "x".repeat(9000));
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid + "/result")).thenReturn(result);
        ObjectNode status = mapper.createObjectNode();
        status.put("set", false);  // loop finished after this fire
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid)).thenReturn(status);

        poller.poll();

        verify(telegram).sendMessage(eq(1L), argThat((String s) -> s.length() < 3700));
    }
}
