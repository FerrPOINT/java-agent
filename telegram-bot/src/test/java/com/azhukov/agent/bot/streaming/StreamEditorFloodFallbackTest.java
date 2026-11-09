package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.rich.RichMessageSupport;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for P2-16: Streaming flood strikes fallback + redundant edit skip.
 * <p>
 * 4a: After 3 consecutive flood control failures, enters fallback mode —
 *     stops editing messages, buffers content, and sends buffered content
 *     as a new message on finalize.
 * 4b: Skips edit if new content is identical to already-displayed content.
 * <p>
 * L7: Note — these tests use Thread.sleep to wait for the edit interval to elapse.
 * This is inherently flaky under heavy CI load. The sleep values are set to 110ms
 * (10ms above the 100ms edit interval) to provide a small margin. These sleeps test
 * actual throttle timing behavior and cannot be replaced with latches.
 */
class StreamEditorFloodFallbackTest {

    private TelegramClient client;
    private StreamEditor editor;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingSilent(true);
        props.setHeartbeatIntervalSeconds(0); // Disable heartbeat for tests
        editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        editor.init();
        // Mock getMe to return no rich messages support
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of());
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));
        when(client.getLastApiErrorCode()).thenReturn(0);
    }

    // ─── 4a: Flood strikes fallback mode tests ─────────────────────

    @Test
    void draftFinalRetriesConfirmedFormattingRefusalOnceWithoutParseMode() throws Exception {
        var requests = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bot-fixture/sendMessage", exchange -> {
            try (exchange) {
                var request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
                boolean formatted = request.has("parse_mode");
                byte[] response = (formatted
                    ? "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: cannot parse entities\"}"
                    : "{\"ok\":true,\"result\":{\"message_id\":91}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(formatted ? 400 : 200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var props = new com.azhukov.agent.bot.config.BotProperties();
        props.setParseMode("MarkdownV2");
        var transport = new com.azhukov.agent.bot.config.BotConfig().telegramRestClient(mapper).mutate()
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        var client = new TelegramClient(transport, mapper, "-fixture", 0);
        var editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        try {
            editor.init();
            assertThat(editor.finalizeTurn(1L, "ordinary answer", 41L, 55L)).isTrue();
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).path("parse_mode").asText()).isEqualTo("MarkdownV2");
            assertThat(requests.get(1).has("parse_mode")).isFalse();
            assertThat(requests.get(1).path("text").asText()).isEqualTo("ordinary answer");
            assertThat(requests.get(1).path("message_thread_id").asInt()).isEqualTo(55);
            assertThat(requests).allSatisfy(request -> {
                var reply = request.path("reply_parameters");
                assertThat(reply.path("message_id").asInt()).isEqualTo(41);
                assertThat(reply.path("allow_sending_without_reply").asBoolean()).isTrue();
                assertThat(request.has("reply_to_message_id")).isFalse();
            });
        } finally {
            editor.destroy();
            server.stop(0);
        }
    }

    @Test
    void splitHtmlFinalKeepsEveryCharacterAndCompleteEntities() {
        var props = new BotProperties();
        props.setParseMode("HTML");
        var htmlEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        htmlEditor.init();
        try {
            String input = "&".repeat(811) + "🙂" + "<".repeat(501) + ">";
            when(client.sendMessageChecked(eq(123L), anyString(), eq("HTML"), any(), any(), isNull(), eq(false)))
                .thenReturn(Optional.of(99L));
            assertThat(htmlEditor.finalizeTurn(123L, input, 0L, 0L)).isTrue();
            var parts = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(2)).sendMessageChecked(eq(123L), parts.capture(), eq("HTML"), any(), any(), isNull(), eq(false));
            var decoded = new StringBuilder();
            for (String part : parts.getAllValues()) {
                assertThat(part.length()).isLessThanOrEqualTo(4096);
                String body = part.substring(part.indexOf(' ') + 1);
                decoded.append(body.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
            }
            assertThat(decoded.toString()).isEqualTo(input);
        } finally {
            htmlEditor.destroy();
        }
    }

    @Test
    void discardStreamPreviewUsesCurrentMessageAndCleansSessionOnFailure() {
        var session = editor.sessionFor(123L);
        session.currentMessageId.set(42L);
        when(client.deleteMessage(123L, 42L)).thenThrow(new RuntimeException("unavailable"));
        editor.discardStreamPreview(123L);
        verify(client).deleteMessage(123L, 42L);
        assertThat(editor.sessionFor(123L)).isNotSameAs(session);
    }

    @Test
    void disabledPreviewUsesFinalTextWithFooter() {
        var session = editor.sessionFor(123L);
        session.currentMessageId.set(42L);
        session.streamingDisabled = true;
        when(client.sendMessageChecked(eq(123L), anyString(), eq("MarkdownV2"), any(), any(), isNull(), eq(false)))
            .thenReturn(Optional.of(99L));
        assertThat(editor.finalizeStream(123L, 42L, "final answer with footer")).isTrue();
        verify(client).sendMessageChecked(eq(123L), eq("final answer with footer"), eq("MarkdownV2"), any(), any(), isNull(), eq(false));
    }

    @Test
    void floodFallbackFormatsFinalMarkdownOnlyOnce() {
        var session = editor.sessionFor(123L);
        session.currentMessageId.set(42L);
        session.streamingDisabled = true;
        editor.editStream(123L, 42L, "value_with_underscore");
        when(client.sendMessageChecked(eq(123L), anyString(), eq("MarkdownV2"), any(), any(), isNull(), eq(false)))
            .thenReturn(Optional.of(99L));
        assertThat(editor.finalizeStream(123L, 42L, "value_with_underscore")).isTrue();
        verify(client).sendMessageChecked(eq(123L), eq("value\\_with\\_underscore"), eq("MarkdownV2"), any(), any(), isNull(), eq(false));
    }

    @Test
    void oversizedFinalPreservesForumThread() {
        editor.sessionFor(123L).messageThreadId = 77L;
        when(client.sendMessageChecked(eq(123L), anyString(), eq("MarkdownV2"), any(), eq(77), isNull(), eq(false)))
            .thenReturn(Optional.of(1L));
        assertThat(editor.finalizeStream(123L, 42L, "answer".repeat(900))).isTrue();
        verify(client, atLeastOnce()).sendMessageChecked(eq(123L), contains("answer"), eq("MarkdownV2"), any(), eq(77), isNull(), eq(false));
        verify(client, never()).editMessageText(eq(123L), eq(42L), anyString(), any(), anyBoolean());
    }

    @Test
    void multipartFinalDoesNotConvertAlreadyFormattedMarkdownTwice() {
        var session = editor.sessionFor(123L);
        session.currentMessageId.set(42L);
        String text = "prefix_".repeat(700);
        when(client.sendMessageChecked(eq(123L), anyString(), eq("MarkdownV2"), any(), any(), isNull(), eq(false)))
            .thenReturn(Optional.of(1L));
        assertThat(editor.finalizeStream(123L, 42L, text)).isTrue();
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).sendMessageChecked(eq(123L), sent.capture(), eq("MarkdownV2"), any(), any(), isNull(), eq(false));
        String restored = sent.getAllValues().stream()
            .map(chunk -> chunk.replaceFirst("^\\\\\\(\\d+/\\d+\\\\\\) ", ""))
            .collect(java.util.stream.Collectors.joining()).replace("\\_", "_");
        assertThat(restored).isEqualTo(text);
    }

    @Test
    void draftRichFinalUsesOriginalForumThread() {
        var rich = mock(RichMessageSupport.class);
        var props = new BotProperties();
        var richEditor = new StreamEditor(client, props, new MediaDeliveryService(), rich);
        richEditor.init();
        var session = richEditor.sessionFor(123L);
        session.useDraftStreaming = true;
        session.messageThreadId = 77L;
        when(rich.shouldAttemptRich(anyString())).thenReturn(true);
        when(rich.sendRichMessage(eq(123L), anyString(), isNull(), eq(77L))).thenReturn(Optional.of(99L));
        assertThat(richEditor.finalizeStream(123L, -1L, "final response")).isTrue();
        verify(rich).sendRichMessage(eq(123L), anyString(), isNull(), eq(77L));
    }

    @Test
    void draftFinalUsesOriginalForumThread() {
        var session = editor.sessionFor(123L);
        session.useDraftStreaming = true;
        session.messageThreadId = 77L;
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), eq(77), isNull(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        assertThat(editor.finalizeStream(123L, -1L, "final response")).isTrue();
        verify(client).sendMessageChecked(eq(123L), anyString(), any(), any(), eq(77), isNull(), anyBoolean());
    }

    @Test
    void multipartFinalStopsWhenMiddleChunkIsRejected() {
        String text = "a".repeat(4000) + "b".repeat(4000) + "c".repeat(1000);
        var session = editor.sessionFor(123L);
        session.useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(41L), Optional.empty(), Optional.of(42L));
        assertThat(editor.finalizeStream(123L, -1L, text)).isFalse();
        verify(client, times(2)).sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean());
        assertThat(editor.hasUnconfirmedDelivery(123L)).isTrue();
        assertThat(editor.finalizeStream(123L, -1L, text)).isFalse();
        verify(client, times(2)).sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean());
    }

    @Test
    void partialFinalThenFloodPastDeadlineStaysPartial() {
        var session = editor.sessionFor(123L);
        session.useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(41L))
            .thenThrow(new com.azhukov.agent.bot.client.TelegramApiException(429, "rate limit", 270));
        assertThat(editor.finalizeStream(123L, -1L, "x".repeat(9000))).isFalse();
        assertThat(editor.hasUnconfirmedDelivery(123L)).isTrue();
        verify(client, times(2)).sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean());
    }

    @Test
    void unknownShortFinalReceiptMarksOutcomeUncertain() {
        var session = editor.sessionFor(123L);
        session.useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.empty());
        assertThat(editor.finalizeStream(123L, -1L, "answer")).isFalse();
        verify(client).sendMessageChecked(eq(123L), eq("answer"), any(), any(), any(), isNull(), anyBoolean());
        assertThat(editor.hasUnconfirmedDelivery(123L)).isTrue();
    }

    @Test
    void confirmedRefusalBeyondDeadlineCompletesWithoutReplay() {
        editor.sessionFor(123L).useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), eq("answer"), any(), any(), any(), isNull(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 31, "rate limited"));

        assertThat(editor.finalizeStream(123L, -1L, "answer")).isFalse();
        assertThat(editor.sealDeferredDelivery(123L)).isCompletedWithValue(false);
        verify(client).sendMessageChecked(eq(123L), eq("answer"), any(), any(), any(), isNull(), anyBoolean());
    }

    @Test
    void cancellationDropsRejectedFragmentAndQueuedFinal() {
        editor.sessionFor(123L).useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), eq("first"), any(), any(), any(), isNull(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 15, "rate limited"));
        editor.onSegmentBreak(123L, -1L, "first");
        assertThat(editor.finalizeStream(123L, -1L, "final")).isFalse();
        var delivery = editor.sealDeferredDelivery(123L);
        assertThat(delivery).isNotDone();

        editor.cancelDeferredDelivery(123L);
        assertThat(delivery).isCompletedWithValue(false);
        verify(client, never()).sendMessageChecked(eq(123L), eq("final"), any(), any(), any(), isNull(), anyBoolean());
    }

    @Test
    void draftFinalSplitsWhenMarkdownEscapingExceedsTelegramLimit() {
        var session = editor.sessionFor(123L);
        session.useDraftStreaming = true;
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        assertThat(editor.finalizeStream(123L, -1L, "_".repeat(3000))).isTrue();
        var sent = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client, atLeast(2)).sendMessageChecked(eq(123L), sent.capture(), any(), any(), any(), isNull(), anyBoolean());
        assertThat(sent.getAllValues()).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(4096));
    }

    @Test
    void finalMultiChunkSendStopsAtFirstUnacceptedChunk() {
        String text = "a".repeat(9000);
        when(client.sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.empty(), Optional.of(99L));
        assertThat(editor.sendFormattedFinalMessage(123L, text)).isEmpty();
        verify(client, times(1)).sendMessageChecked(eq(123L), anyString(), any(), any(), any(), isNull(), anyBoolean());
    }

    @Test
    void floodFallback_entersFallbackModeAfter3Strikes() throws InterruptedException {
        // Start stream successfully
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // All edits fail with 429
        when(client.getLastApiErrorCode()).thenReturn(429);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);

        // First flood failure
        editor.editStream(123L, 42L, "Hello world update 1");
        // Actual timing: wait for adaptive interval (doubles to 200ms after first 429)
        Thread.sleep(110); // timing-assertion
        // Second flood failure — interval has now doubled to 200ms, wait long enough
        editor.editStream(123L, 42L, "Hello world update 2");
        // Actual timing: wait for adaptive interval (doubles to 400ms after second 429)
        Thread.sleep(500); // timing-assertion
        // Third flood failure — interval has now doubled to 400ms, wait long enough
        editor.editStream(123L, 42L, "Hello world update 3");
        // Actual timing: wait for adaptive interval (doubles to 800ms after third 429)
        Thread.sleep(1000); // timing-assertion

        // Now streaming should be disabled — further edits should not call editMessageText
        // Actual timing: wait past current throttle interval
        Thread.sleep(110); // timing-assertion
        // L5: Verify that editMessageText is NOT called in fallback mode.
        // Count editMessageText invocations before and after — they should be the same.
        long editTextCountBefore = mockingDetails(client).getInvocations().stream()
            .filter(inv -> inv.getMethod().getName().equals("editMessageText")).count();
        editor.editStream(123L, 42L, "Hello world update 4");
        long editTextCountAfter = mockingDetails(client).getInvocations().stream()
            .filter(inv -> inv.getMethod().getName().equals("editMessageText")).count();
        assertThat(editTextCountAfter).as("editMessageText should not be called in fallback mode").isEqualTo(editTextCountBefore);
    }

    @Test
    void floodFallback_sendsBufferedContentOnFinalize() throws InterruptedException {
        // Start stream successfully
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // All edits fail with 429
        when(client.getLastApiErrorCode()).thenReturn(429);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);

        // Trigger 3 flood failures to enter fallback mode
        editor.editStream(123L, 42L, "Hello world update 1");
        // Actual timing: wait for adaptive interval
        Thread.sleep(110); // timing-assertion
        editor.editStream(123L, 42L, "Hello world update 2");
        // Actual timing: wait for adaptive interval
        Thread.sleep(500); // timing-assertion
        editor.editStream(123L, 42L, "Hello world update 3");
        // Actual timing: wait for adaptive interval
        Thread.sleep(1000); // timing-assertion

        // Buffer more content while in fallback mode
        // Actual timing: wait past current throttle interval
        Thread.sleep(110); // timing-assertion
        editor.editStream(123L, 42L, "Hello world final buffered content");

        // Finalize — should send buffered content as new message
        when(client.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        boolean result = editor.finalizeStream(123L, 42L, "Hello world final buffered content");

        assertThat(result).isTrue();
        // Flood fallback now sends formatted text (parseMode=MarkdownV2) — Hermes parity.
        // Anchor on the buffered content to exclude the earlier delayed-start message.
        verify(client).sendMessageChecked(eq(123L), contains("buffered content"), eq("MarkdownV2"), any(), any(), isNull(), anyBoolean());
        // The replacement must arrive before the draft is removed. This prevents
        // a Telegram 429 from turning into total response loss.
        var order = inOrder(client);
        order.verify(client).sendMessageChecked(eq(123L), contains("buffered content"), eq("MarkdownV2"), any(), any(), isNull(), anyBoolean());
        order.verify(client).deleteMessage(123L, 42L);
    }

    @Test
    void floodFallback_sendFailurePreservesVisibleDraftAndSession() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        editor.startStream(123L, "visible draft");
        Thread.sleep(110);
        when(client.getLastApiErrorCode()).thenReturn(429);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);
        editor.editStream(123L, 42L, "one");
        Thread.sleep(110);
        editor.editStream(123L, 42L, "two");
        Thread.sleep(500);
        editor.editStream(123L, 42L, "three");
        Thread.sleep(1_000);
        // The final replacement is rate-limited. It must not delete the draft.
        when(client.sendMessageChecked(anyLong(), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.empty());

        assertThat(editor.finalizeStream(123L, 42L, "final content")).isFalse();
        verify(client, never()).deleteMessage(123L, 42L);
        assertThat(editor.sessionFor(123L).streamingDisabled).isTrue();
        assertThat(editor.hasUnconfirmedDelivery(123L)).isTrue();
    }

    @Test
    void floodFallback_resetsStrikesOnSuccess() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);
        when(client.getLastApiErrorCode()).thenReturn(429);

        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // Two flood failures (not enough to trigger fallback)
        editor.editStream(123L, 42L, "Update 1");
        // Actual timing: wait for adaptive interval
        Thread.sleep(110); // timing-assertion
        editor.editStream(123L, 42L, "Update 2");
        // Actual timing: wait for adaptive interval
        Thread.sleep(110); // timing-assertion

        // Now a successful edit — should reset flood strikes
        when(client.getLastApiErrorCode()).thenReturn(0);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        // Actual timing: wait long enough for the increased interval
        Thread.sleep(500); // timing-assertion
        boolean success = editor.editStream(123L, 42L, "Update 3 success");
        assertThat(success).isTrue();

        // After reset, 2 more failures should NOT trigger fallback (strikes were reset)
        when(client.getLastApiErrorCode()).thenReturn(429);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);

        // Actual timing: wait for adaptive interval
        Thread.sleep(500); // timing-assertion
        editor.editStream(123L, 42L, "Update 4");
        // Actual timing: wait for adaptive interval
        Thread.sleep(500); // timing-assertion
        editor.editStream(123L, 42L, "Update 5");

        // Next edit should still try to edit (not in fallback mode yet — only 2 strikes)
        when(client.getLastApiErrorCode()).thenReturn(0);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);
        // Actual timing: wait for increased interval after 2 failures
        Thread.sleep(2000); // timing-assertion
        boolean stillEditing = editor.editStream(123L, 42L, "Update 6");
        assertThat(stillEditing).isTrue();
    }

    // ─── 4b: Redundant edit skip tests ─────────────────────────────

    @Test
    void redundantEditSkip_skipsIdenticalContent() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // First edit — should go through
        editor.editStream(123L, 42L, "Hello world updated");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // Second edit with same content — should be skipped
        int editCountBefore = mockingDetails(client).getInvocations().size();
        boolean result = editor.editStream(123L, 42L, "Hello world updated");
        int editCountAfter = mockingDetails(client).getInvocations().size();

        // Should return true (treated as success) but not actually call editMessageText
        assertThat(result).isTrue();
        // The edit count should not have increased (no new editMessageText call)
        // Note: we need to count only editMessageText invocations
        verify(client, times(1)).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    void redundantEditSkip_allowsDifferentContent() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // First edit
        editor.editStream(123L, 42L, "Hello world updated");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // Second edit with different content — should go through
        editor.editStream(123L, 42L, "Hello world updated again");

        // Should have called editMessageText twice (two different edits)
        verify(client, times(2)).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    void redundantEditSkip_differentCursorStillSkips() throws InterruptedException {
        // The cursor is appended to the formatted text, so if the content is the same,
        // the cursor makes the full text the same too — so it should still skip.
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello world");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // Edit with same content
        editor.editStream(123L, 42L, "Hello world updated");
        // Actual timing: wait past 100ms throttle interval
        Thread.sleep(110); // timing-assertion

        // Same content again — skip
        editor.editStream(123L, 42L, "Hello world updated");

        // Only 1 editMessageText call for the first edit (second was skipped)
        verify(client, times(1)).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
    }
}