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
 * Heartbeat result delivery with ACK semantics (Hermes delivery-ledger
 * parity): a failed send must NOT lose the message; the bot ACKs only after
 * a successful Telegram send. Poisoned results are dropped after 5 nacks.
 */
class HeartbeatDeliveryPollerTest {

    private AgentBackendClient backend;
    private TelegramClient telegram;
    private HeartbeatDeliveryPoller poller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        backend = mock(AgentBackendClient.class);
        telegram = mock(TelegramClient.class);
        BotProperties props = mock(BotProperties.class);
        when(props.isCronDeliveryEnabled()).thenReturn(true);
        poller = new HeartbeatDeliveryPoller(backend, telegram, props);
    }

    private void stubResult(UUID sid, String text) {
        ObjectNode result = mapper.createObjectNode();
        result.put("hasResult", text != null);
        if (text != null) result.put("result", text);
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid + "/result"))
            .thenReturn(result);
    }

    private void stubStatus(UUID sid, boolean set) {
        ObjectNode status = mapper.createObjectNode();
        status.put("set", set);
        when(backend.suggestionGet("/api/v1/agent/cron/heartbeat/" + sid)).thenReturn(status);
    }

    @Test
    void successfulSendAcks() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 42L);
        stubResult(sid, "Nothing changed.");
        stubStatus(sid, true);
        when(telegram.sendMessage(eq(42L), anyString())).thenReturn(Optional.of(7L));

        poller.poll();

        verify(telegram).sendMessage(eq(42L), contains("Nothing changed"));
        verify(backend).suggestionPost("/api/v1/agent/cron/heartbeat/" + sid + "/result/ack");
    }

    @Test
    void failedSendNacksButResultSurvives() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 42L);
        stubResult(sid, "payload");
        stubStatus(sid, true);
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.empty());
        ObjectNode notDropped = mapper.createObjectNode();
        notDropped.put("drop", false);
        when(backend.suggestionPost(contains("/result/nack"))).thenReturn(notDropped);

        poller.poll();

        // NO ack — the result stays server-side for the next tick
        verify(backend, never()).suggestionPost(contains("/result/ack"));
        verify(backend).suggestionPost(contains("/result/nack"));
    }

    @Test
    void poisonedResultDroppedAfterNackSaysDrop() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 42L);
        stubResult(sid, "poison");
        stubStatus(sid, true);
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.empty());
        ObjectNode drop = mapper.createObjectNode();
        drop.put("drop", true);
        when(backend.suggestionPost(contains("/result/nack"))).thenReturn(drop);

        poller.poll();

        // drop=true forces the ack that removes the poisoned result
        verify(backend).suggestionPost(contains("/result/ack"));
    }

    @Test
    void emptyResultIsAckedImmediately() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 42L);
        stubResult(sid, "");
        stubStatus(sid, true);

        poller.poll();

        verify(telegram, never()).sendMessage(anyLong(), anyString());
        verify(backend).suggestionPost(contains("/result/ack"));
    }

    @Test
    void clearsWatchWhenHeartbeatGone() {
        UUID sid = UUID.randomUUID();
        poller.watch(sid, 1L);
        stubResult(sid, null);
        stubStatus(sid, false);

        poller.poll();
        poller.poll();

        verify(backend, times(2)).suggestionGet(anyString());
    }
}
