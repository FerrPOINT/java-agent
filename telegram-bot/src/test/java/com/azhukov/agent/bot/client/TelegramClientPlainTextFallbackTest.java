package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for P1-6: Plain-text fallback on MarkdownV2 parse failure.
 * <p>
 * When Telegram returns a "can_parse_entities" error, the client should
 * retry the same message without parse_mode (plain text).
 */
@ExtendWith(MockitoExtension.class)
class TelegramClientPlainTextFallbackTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock
    private RestClient.RequestBodySpec bodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private TelegramClient client;

    @BeforeEach
    void setUp() {
        client = new TelegramClient(restClient, new ObjectMapper(), "BOT_TOKEN", 0);
    }

    private void stubPostChain() {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyMap())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
    }

    private TelegramResponse successResponseWithMessageId(long messageId) {
        return new TelegramResponse(true, null, "OK", Map.of("message_id", messageId), null);
    }

    private TelegramResponse errorResponse(int errorCode, String description) {
        return new TelegramResponse(false, errorCode, description, null, null);
    }

    @Test
    @DisplayName("sendMessage falls back to plain text on can_parse_entities error")
    void sendMessage_fallsBackToPlainTextOnParseError() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "Bad Request: can't parse entities: Character '-' is reserved"))
            .thenReturn(successResponseWithMessageId(99L));

        Optional<Long> result = client.sendMessage(123L, "**bad markdown**", "MarkdownV2", null, null, false);

        assertThat(result).contains(99L);
        // Should have called post() twice (initial + plain-text retry)
        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("sendMessage does NOT fall back when parse_mode is null")
    void sendMessage_noFallbackWhenParseModeIsNull() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "can_parse_entities"));

        Optional<Long> result = client.sendMessage(123L, "text", null, null, null, false);

        assertThat(result).isEmpty();
        verify(restClient, times(1)).post();
    }

    @Test
    @DisplayName("sendMessage plain-text fallback failure returns empty")
    void sendMessage_plainTextFallbackFailureReturnsEmpty() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "can_parse_entities"))
            .thenReturn(errorResponse(400, "some other error"));

        Optional<Long> result = client.sendMessage(123L, "**bad**", "MarkdownV2", null, null, false);

        assertThat(result).isEmpty();
        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("editMessageText falls back to plain text on can_parse_entities error")
    void editMessageText_fallsBackToPlainTextOnParseError() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "can_parse_entities"))
            .thenReturn(successResponseWithMessageId(0)); // editMessageText returns true if present

        boolean result = client.editMessageText(123L, 42L, "**bad markdown**", "MarkdownV2", false);

        assertThat(result).isTrue();
        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("editMessageText does NOT fall back when parse_mode is null")
    void editMessageText_noFallbackWhenParseModeIsNull() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "can_parse_entities"));

        boolean result = client.editMessageText(123L, 42L, "text", null, false);

        assertThat(result).isFalse();
        verify(restClient, times(1)).post();
    }

    @Test
    @DisplayName("editMessageText rethrows 429 even during parse error fallback")
    void editMessageText_rethrows429FromFallback() {
        stubPostChain();
        when(responseSpec.body(TelegramResponse.class))
            .thenReturn(errorResponse(400, "can_parse_entities"))
            .thenReturn(errorResponseWithParams(429, "Too Many Requests"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            client.editMessageText(123L, 42L, "**bad**", "MarkdownV2", false))
            .isInstanceOf(TelegramApiException.class)
            .satisfies(ex -> assertThat(((TelegramApiException) ex).isRateLimit()).isTrue());
    }

    private TelegramResponse errorResponseWithParams(int errorCode, String description) {
        return new TelegramResponse(false, errorCode, description, null, null);
    }
}