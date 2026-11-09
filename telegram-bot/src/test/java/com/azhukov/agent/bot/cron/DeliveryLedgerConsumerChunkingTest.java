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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(Optional.of(1L));

        consumer().deliverBatchForTest(List.of(item(payload)));

        assertSentText(payload);
    }

    @Test
    void coalescedResultsKeepTextAtTheChunkBoundary() {
        var batch = java.util.stream.IntStream.range(0, 7)
            .mapToObj(i -> new DeliveryLedgerConsumer.ClaimedItem(
                "id-" + i, "token-" + i, "cron_execution", String.valueOf(i),
                "telegram", "100200300", "", "🚀 ".repeat(250) + "RESULT-" + i, 1))
            .toList();
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(Optional.of(1L));

        consumer().deliverBatchForTest(batch);

        assertSentText(DeliveryLedgerConsumer.formatCoalescedBatch(batch));
    }

    private void assertSentText(String expected) {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram, org.mockito.Mockito.atLeast(2))
            .sendMessageChecked(anyLong(), sent.capture(), any(), any(), any(), any(), anyBoolean(), any());
        StringBuilder delivered = new StringBuilder();
        for (String chunk : sent.getAllValues()) {
            assertThat(chunk.length()).isLessThanOrEqualTo(MessageSplitter.TELEGRAM_MAX_LENGTH);
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            delivered.append(chunk.replaceFirst("^\\(\\d+/\\d+\\) ", ""));
        }
        assertThat(delivered.toString()).isEqualTo(expected);
    }

    @Test
    void firstChunkFailureFencesItemAsUnknownNotRetried() {
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(Optional.empty());

        consumer().deliverBatchForTest(List.of(item("short payload")));

        // An empty result may mean a timeout after acceptance; do not retry blindly.
        verify(telegram, times(1)).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void unsupportedPlatformIsDroppedWithoutSend() {
        DeliveryLedgerConsumer.ClaimedItem local =
            new DeliveryLedgerConsumer.ClaimedItem("id-1", "token-1", "cron_execution", "42",
                "discord", "123", "", "payload", 1);

        consumer().deliverBatchForTest(List.of(local));

        verify(telegram, never()).sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
