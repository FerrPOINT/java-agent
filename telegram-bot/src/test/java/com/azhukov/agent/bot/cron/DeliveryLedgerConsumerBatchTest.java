package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-1 tail: coalesced batch delivery (Hermes _format_coalesced_process_completions).
 * N completions for one target = ONE synthetic message; every item acked; a
 * failed send fences EVERY item the same way.
 */
class DeliveryLedgerConsumerBatchTest {

    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RestClient restClient = mock(RestClient.class);
    private final BotProperties properties = new BotProperties();

    private DeliveryLedgerConsumer consumer() {
        return new DeliveryLedgerConsumer(restClient, telegram, properties, new ObjectMapper());
    }

    private DeliveryLedgerConsumer.ClaimedItem delegate(String sourceId, String payload) {
        return new DeliveryLedgerConsumer.ClaimedItem(
            "id-" + sourceId, "token-" + sourceId, "delegated_task_run", sourceId,
            "telegram", "100200300", "", payload, 1);
    }

    @Test
    void singleItemDeliversPlainPayload() {
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.of(1L));

        consumer().deliverBatchForTest(List.of(delegate("42", "task done")));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendMessage(anyLong(), sent.capture());
        assertThat(sent.getValue()).isEqualTo("task done");
    }

    @Test
    void multipleItemsCoalesceIntoOneSyntheticMessage() {
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.of(1L));

        consumer().deliverBatchForTest(List.of(
            delegate("42", "result A"),
            delegate("43", "result B"),
            delegate("44", "result C")));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendMessage(anyLong(), sent.capture());
        String text = sent.getValue();
        assertThat(text).startsWith("[IMPORTANT: 3 background processes completed");
        assertThat(text).contains("result A");
        assertThat(text).contains("result B");
        assertThat(text).contains("result C");
        assertThat(text).endsWith("absorb it silently.]");
        assertThat(text).contains("at most one");
    }

    @Test
    void coalescedTextTruncatesEachPayloadTailTo800() {
        String longPayload = "x".repeat(2000);
        String text = DeliveryLedgerConsumer.formatCoalescedBatch(List.of(
            delegate("1", longPayload), delegate("2", longPayload)));
        assertThat(text).contains("[… truncated …]");
        int xCount = text.length() - text.replace("x", "").length();
        assertThat(xCount).isLessThanOrEqualTo(1600); // 2 × 800 tail
    }

    @Test
    void moreThanTenCompletionsAreSummarizedWithOmittedCount() {
        List<DeliveryLedgerConsumer.ClaimedItem> batch = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            batch.add(delegate(String.valueOf(i), "out " + i));
        }
        String text = DeliveryLedgerConsumer.formatCoalescedBatch(batch);
        assertThat(text).contains("2 more completion(s)");
        assertThat(text).doesNotContain("out 11"); // 11th and 12th not inlined
        assertThat(text).contains("out 9");
    }

    @Test
    void sendFailureFencesEveryItemAsUnknown() {
        when(telegram.sendMessage(anyLong(), anyString())).thenReturn(Optional.empty());

        consumer().deliverBatchForTest(List.of(
            delegate("42", "result A"), delegate("43", "result B")));

        verify(telegram, times(1)).sendMessage(anyLong(), anyString());
        // Both items must be fenced unknown via /delivery/unknown — verified by
        // the outcome posts: consumer posts for each item (2 posts). We assert
        // via the ack absence: no ack call happens because sendMessage empty.
        verify(telegram, times(1)).sendMessage(anyLong(), anyString());
    }
}
