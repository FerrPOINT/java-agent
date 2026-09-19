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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentMatchers;

/**
 * Hermes delivery.py parity (upstream c961e5bb69 wave): a platform REFUSAL is
 * a known failure — the ledger releases the row with the platform's own
 * retry_after for flood refusals instead of fencing it as unknown (which is
 * never retried and silently loses the notification).
 */
class DeliveryLedgerConsumerRefusalTest {

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

    private RestClient.RequestBodySpec specMock;

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodySpec spec() {
        RestClient.RequestBodySpec spec = mock(RestClient.RequestBodySpec.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(ArgumentMatchers.<String>any(), ArgumentMatchers.any(Object[].class))).thenReturn(spec);
        when(spec.contentType(any())).thenReturn(spec);
        when(spec.body(anyString())).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        specMock = spec;
        return spec;
    }

    @Test
    void floodRefusalReleasesWithPlatformRetryAfter() {
        spec();
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(95, "429: Too Many Requests"));

        consumer().deliverBatchForTest(List.of(
            delegate("42", "result A"), delegate("43", "result B")));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(specMock, times(2)).body(body.capture());
        // Every item is RELEASED (retryable) with the flood category carrying
        // the platform's own wait — never fenced as unknown.
        List<String> posts = body.getAllValues();
        long releases = posts.stream()
            .filter(p -> p.contains("flood_control:95") && p.contains("retry_after=95s"))
            .count();
        assertThat(releases).isEqualTo(2);
    }

    @Test
    void definiteApiRefusalReleasesAsSendRefused() {
        spec();
        when(telegram.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(-1, "400: Bad Request: chat not found"));

        consumer().deliverBatchForTest(List.of(delegate("44", "payload")));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(specMock, times(1)).body(body.capture());
        assertThat(body.getAllValues().stream()
            .filter(p -> p.contains("send_refused") && !p.contains("flood"))
            .count()).isEqualTo(1);
    }
}
