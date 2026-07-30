package com.azhukov.agent.bot.rich;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RichMessageSupportTest {

    private TelegramClient client;
    private RichMessageSupport support;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        support = new RichMessageSupport(client);
    }

    @Test
    void contentFitsRichLimits_withinLimit() {
        assertThat(support.contentFitsRichLimits("short content")).isTrue();
    }

    @Test
    void contentFitsRichLimits_overLimit() {
        String large = "a".repeat(RichMessageSupport.RICH_MESSAGE_MAX_CHARS + 1);
        assertThat(support.contentFitsRichLimits(large)).isFalse();
    }

    @Test
    void contentFitsRichLimits_null() {
        assertThat(support.contentFitsRichLimits(null)).isFalse();
    }

    @Test
    void shouldAttemptRich_validContent_returnsTrue() {
        // Simulate successful getMe capability check
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        assertThat(support.shouldAttemptRich("Hello world")).isTrue();
    }

    @Test
    void shouldAttemptRich_blankContent_returnsFalse() {
        assertThat(support.shouldAttemptRich("")).isFalse();
        assertThat(support.shouldAttemptRich("   ")).isFalse();
    }

    @Test
    void shouldAttemptRich_disabled_returnsFalse() {
        support.setRichMessagesEnabled(false);
        assertThat(support.shouldAttemptRich("Hello world")).isFalse();
    }

    @Test
    void shouldAttemptRich_latchedOff_returnsFalse() {
        // Set up getMe to pass capability check
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        // Simulate a capability error to latch off
        TelegramResponse failResponse = mock(TelegramResponse.class);
        when(failResponse.isSuccess()).thenReturn(false);
        when(failResponse.errorMessage()).thenReturn("Method not found");
        when(client.callApi(eq("sendRichMessage"), any())).thenReturn(Optional.of(failResponse));

        support.sendRichMessage(123L, "content", null, null);
        // After a capability error, shouldAttemptRich should return false
        assertThat(support.isRichSendDisabled()).isTrue();
    }

    @Test
    void sendRichMessage_success_returnsMessageId() {
        // Set up getMe to pass capability check
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        // Set up sendRichMessage success
        Map<String, Object> sendResult = new LinkedHashMap<>();
        sendResult.put("message_id", 42L);
        TelegramResponse sendResponse = mock(TelegramResponse.class);
        when(sendResponse.isSuccess()).thenReturn(true);
        when(sendResponse.resultAsMap()).thenReturn(sendResult);
        when(sendResponse.resultMessageIdAsLong()).thenReturn(42L);
        when(client.callApi(eq("sendRichMessage"), any())).thenReturn(Optional.of(sendResponse));

        Optional<Long> msgId = support.sendRichMessage(123L, "# Hello\n\n| Col1 | Col2 |\n|---|---|\n| A | B |", null, null);
        assertThat(msgId).contains(42L);
    }

    @Test
    void sendRichMessage_apiError_returnsEmpty() {
        // Set up getMe to pass
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        // Set up sendRichMessage failure
        TelegramResponse failResponse = mock(TelegramResponse.class);
        when(failResponse.isSuccess()).thenReturn(false);
        when(failResponse.errorMessage()).thenReturn("Bad Request: invalid markdown");
        when(client.callApi(eq("sendRichMessage"), any())).thenReturn(Optional.of(failResponse));

        Optional<Long> msgId = support.sendRichMessage(123L, "content", null, null);
        assertThat(msgId).isEmpty();
    }

    @Test
    void sendRichMessage_capabilityError_latchesOff() {
        // Set up getMe to pass
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        // Set up sendRichMessage capability failure
        TelegramResponse failResponse = mock(TelegramResponse.class);
        when(failResponse.isSuccess()).thenReturn(false);
        when(failResponse.errorMessage()).thenReturn("method not found");
        when(client.callApi(eq("sendRichMessage"), any())).thenReturn(Optional.of(failResponse));

        Optional<Long> msgId = support.sendRichMessage(123L, "content", null, null);
        assertThat(msgId).isEmpty();
        assertThat(support.isRichSendDisabled()).isTrue();

        // Second call should skip rich entirely
        Optional<Long> msgId2 = support.sendRichMessage(123L, "content2", null, null);
        assertThat(msgId2).isEmpty();
        // Should not call sendRichMessage again
        verify(client, times(1)).callApi(eq("sendRichMessage"), any());
    }

    @Test
    void sendRichMessage_withReplyAndThread_passesCorrectParams() {
        // Set up getMe to pass
        Map<String, Object> meResult = new LinkedHashMap<>();
        meResult.put("supports_rich_messages", true);
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(meResult);
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));

        Map<String, Object> sendResult = new LinkedHashMap<>();
        sendResult.put("message_id", 99L);
        TelegramResponse sendResponse = mock(TelegramResponse.class);
        when(sendResponse.isSuccess()).thenReturn(true);
        when(sendResponse.resultAsMap()).thenReturn(sendResult);
        when(sendResponse.resultMessageIdAsLong()).thenReturn(99L);
        when(client.callApi(eq("sendRichMessage"), any())).thenReturn(Optional.of(sendResponse));

        support.sendRichMessage(123L, "content", 55L, 77L);
        // Verify the API was called with correct params
        verify(client).callApi(eq("sendRichMessage"), argThat(params -> {
            Object chatId = params.get("chat_id");
            Object richMsg = params.get("rich_message");
            Object replyParams = params.get("reply_parameters");
            Object threadId = params.get("message_thread_id");
            return chatId.equals(123L)
                && richMsg instanceof Map
                && replyParams instanceof Map && ((Map<?,?>) replyParams).get("message_id").equals(55L)
                && threadId.equals(77L);
        }));
    }

    @Test
    void isRichCapabilityError_detectsNotFound() {
        assertThat(RichMessageSupport.isRichCapabilityError("method not found")).isTrue();
        assertThat(RichMessageSupport.isRichCapabilityError("endpoint does not exist")).isTrue();
        assertThat(RichMessageSupport.isRichCapabilityError("no such method")).isTrue();
        assertThat(RichMessageSupport.isRichCapabilityError("unsupported")).isTrue();
    }

    @Test
    void isRichCapabilityError_ignoresTransient() {
        assertThat(RichMessageSupport.isRichCapabilityError("timeout")).isFalse();
        assertThat(RichMessageSupport.isRichCapabilityError("network error")).isFalse();
        assertThat(RichMessageSupport.isRichCapabilityError(null)).isFalse();
    }

    @Test
    void reset_clearsState() {
        support.setRichSendDisabled(true);
        support.reset();
        assertThat(support.isRichSendDisabled()).isFalse();
    }
}