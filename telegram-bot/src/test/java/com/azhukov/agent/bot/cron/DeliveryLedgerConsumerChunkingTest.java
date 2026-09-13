package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-1 cutover: the ledger consumer is the sole cron delivery lane. P-03
 * (Hermes delivery.py): Telegram is chunking-capable, so the FULL output is
 * delivered — MessageSplitter splits UTF-16-safely, no tail is dropped, and
 * partial chunk failures are fenced as unknown (never auto-retried).
 */
class DeliveryLedgerConsumerChunkingTest {

    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RestClient restClient = mock(RestClient.class);
    private final BotProperties properties = new BotProperties();

    private DeliveryLedgerConsumer consumer() {
        return new DeliveryLedgerConsumer(restClient, telegram, properties, new ObjectMapper());
    }

    private DeliveryLedgerConsumer.ClaimedItem item(String payload) {
        return new DeliveryLedgerConsumer.ClaimedItem(
            "id-1", "token-1", "cron_execution", "42", "telegram", "100200300", "", payload, 1);
    }

    @Test
    void longUnicodeOutputIsChunkedAndFullyDelivered() {
        // 30k chars incl. 4-byte emoji (surrogate pairs) — the UTF-16 trap case
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("Строка #").append(i).append(" 🚀🎯 — данные długie текст.\n");
        }
        String payload = sb.toString();
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.of(1L));

        consumer().deliverForTest(item(payload));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(MessageSplitter.split(payload).size()))
            .sendMessage(anyLong(), sent.capture());
        List<String> chunks = sent.getAllValues();
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
            char last = chunk.charAt(chunk.length() - 1);
            assertThat(Character.isHighSurrogate(last)).isFalse();
        }
        String joined = String.join("", chunks);
        assertThat(joined).contains("Строка #0");
        assertThat(joined).contains("Строка #299");
    }

    @Test
    void firstChunkFailureFencesItemAsUnknownNotRetried() {
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.empty());

        consumer().deliverForTest(item("short payload"));

        // Nothing was sent — but sendMessage conflates failure modes into
        // empty, so the outcome must be unknown (Hermes: never auto-retry a
        // possibly-sent message). No second chunk attempt.
        verify(telegram, times(1)).sendMessage(anyLong(), anyString());
    }

    @Test
    void unsupportedPlatformIsDroppedWithoutSend() {
        DeliveryLedgerConsumer.ClaimedItem local =
            new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "42",
                "discord", "123", "", "payload", 1);

        consumer().deliverForTest(local);

        verify(telegram, never()).sendMessage(anyLong(), anyString());
    }
}
