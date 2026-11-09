package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.rich.RichMessageSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StreamDeliveryRegressionTest {
    private final TelegramClient client = mock(TelegramClient.class);

    private StreamEditor editor(boolean rich, long freshTimeout) {
        var props = new BotProperties();
        props.setParseMode("MarkdownV2");
        props.getRichMessages().setEnabled(rich);
        props.setFreshFinalTimeoutMs(freshTimeout);
        var editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        editor.init();
        return editor;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "sendMessage,stop", "editMessageText,stop", "sendRichMessage,stop",
        "sendMessage,expired", "editMessageText,expired", "sendRichMessage,expired",
        "sendMessage,allowed", "editMessageText,allowed", "sendRichMessage,allowed"
    })
    void finalPostChecksSessionAfterWaitingForPermit(String method, String state) throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var transport = new com.azhukov.agent.bot.config.BotConfig().telegramRestClient(mapper).mutate()
            .requestInterceptor((request, body, execution) -> {
                String path = request.getURI().getPath();
                String apiMethod = path.substring(path.lastIndexOf('/') + 1);
                requests.add(apiMethod);
                String result = apiMethod.equals("getMe")
                    ? "{\"supports_rich_messages\":true}" : "{\"message_id\":42}";
                var response = new org.springframework.mock.http.client.MockClientHttpResponse(
                    ("{\"ok\":true,\"result\":" + result + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    org.springframework.http.HttpStatus.OK);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                return response;
            }).build();
        var telegram = new TelegramClient(transport, mapper, "fixture", 1);
        var props = new BotProperties();
        props.getRichMessages().setEnabled(method.equals("sendRichMessage"));
        var rich = new RichMessageSupport(telegram);
        var editor = new StreamEditor(telegram, props, new MediaDeliveryService(), rich);
        editor.init();
        try {
            if (method.equals("sendRichMessage")) assertThat(rich.shouldAttemptRich("final")).isTrue();
            requests.clear();
            var session = editor.sessionFor(101L);
            long preview = method.equals("editMessageText") ? 9L : -1L;
            session.currentMessageId.set(preview);
            var permit = org.mockito.Mockito.mock(io.github.resilience4j.ratelimiter.RateLimiter.class);
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            org.mockito.Mockito.when(permit.acquirePermission()).thenAnswer(call -> {
                waiting.countDown();
                return release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            });
            org.springframework.test.util.ReflectionTestUtils.setField(telegram, "rateLimiter", permit);
            var send = new java.util.concurrent.FutureTask<>(() -> editor.finalizeStream(101L, preview, "final"));
            try {
                Thread.startVirtualThread(send);
                assertThat(waiting.await(2, SECONDS)).isTrue();
                if (state.equals("stop")) editor.cancelDeferredDelivery(101L);
                if (state.equals("expired")) {
                    synchronized (session) {
                        session.deferredDelivery = new java.util.concurrent.CompletableFuture<>();
                        session.floodDeliveryDeadlineNanos = System.nanoTime() - 1;
                    }
                }
            } finally {
                release.countDown();
            }
            boolean allowed = state.equals("allowed");
            assertThat(send.get(2, SECONDS)).isEqualTo(allowed);
            assertThat(requests).containsExactlyElementsOf(allowed ? java.util.List.of(method) : java.util.List.of());
        } finally {
            editor.destroy();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, -1})
    void onlyConfirmedMissingPreviewCanBeReplaced(int code) {
        when(client.editMessageChecked(eq(101L), eq(9L), eq("final"), eq("MarkdownV2"), anyBoolean(), any())).thenThrow(
            new TelegramApiException(code, code == 400 ? "Bad Request: message to edit not found" : "lost receipt"));
        if (code == 400) {
            when(client.sendMessageChecked(eq(101L), eq("final"), eq("MarkdownV2"), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Optional.of(17L));
        }
        var editor = editor(false, 0);
        try {
            editor.sessionFor(101L).currentMessageId.set(9L);
            assertThat(editor.finalizeStream(101L, 9L, "final")).isEqualTo(code == 400);
            verify(client, times(code == 400 ? 1 : 0))
                .sendMessageChecked(eq(101L), eq("final"), eq("MarkdownV2"), any(), any(), any(), anyBoolean(), any());
        } finally {
            editor.destroy();
        }
    }

    @Test
    void ambiguousRichReceiptDoesNotSendAnotherFinal() {
        var me = mock(TelegramResponse.class);
        when(me.resultAsMap()).thenReturn(Map.of("supports_rich_messages", true));
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(me));
        when(client.callApi(eq("sendRichMessage"), anyMap(), any()))
            .thenThrow(new TelegramApiException(-1, "receipt lost after acceptance"));
        var editor = editor(true, 0);
        try {
            editor.sessionFor(101L).useDraftStreaming = true;
            assertThat(editor.finalizeStream(101L, -1L, "final")).isFalse();
            assertThat(editor.hasUnconfirmedDelivery(101L)).isTrue();
            verify(client).callApi(eq("sendRichMessage"), anyMap(), any());
            verify(client, never()).sendMessageChecked(eq(101L), anyString(), any(), any(), any(), any(), anyBoolean(), any());
        } finally {
            editor.destroy();
        }
    }

    @Test
    @Tag("slow")
    void silentFinalLeavesEarlierDeferredSegmentQueued() throws Exception {
        when(client.sendMessageChecked(eq(101L), eq("earlier"), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenReturn(Optional.of(1L));
        var editor = editor(false, 0);
        try {
            editor.sendFormattedFinalMessage(101L, "earlier");
            assertThat(editor.finalizeStream(101L, -1L, "<think>hidden</think>NO_REPLY")).isFalse();
            assertThat(editor.sealDeferredDelivery(101L).get(5, SECONDS)).isTrue();
            verify(client, times(2)).sendMessageChecked(
                eq(101L), eq("earlier"), any(), any(), any(), isNull(), anyBoolean(), any());
            verifyNoMoreInteractions(client);
        } finally {
            editor.destroy();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 800})
    void markdownRejectionSendsOriginalFragmentsAsPlainText(int repeats) {
        String text = "file_name. ".repeat(repeats).stripTrailing();
        when(client.sendMessageChecked(eq(101L), anyString(), eq("MarkdownV2"), any(), any(), isNull(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(400, -1, "Bad Request: can't parse entities"));
        when(client.sendMessageChecked(eq(101L), anyString(), isNull(), any(), any(), isNull(), anyBoolean(), any()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            assertThat(editor.sendFormattedFinalMessage(101L, text)).contains(17L);
            var plain = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeastOnce()).sendMessageChecked(
                eq(101L), plain.capture(), isNull(), any(), any(), isNull(), anyBoolean(), any());
            String joined = plain.getAllValues().stream()
                .map(part -> part.replaceFirst("^\\(\\d+/\\d+\\) ", ""))
                .collect(java.util.stream.Collectors.joining());
            assertThat(joined).isEqualTo(text);
        } finally {
            editor.destroy();
        }
    }

    @Test
    void oversizedPlainFallbackReplacesPreviewWithBoundedChunks() {
        String text = "**" + "a".repeat(4093) + "**";
        when(client.sendMessageChecked(eq(101L), anyString(), eq("MarkdownV2"), any(), any(), any(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(400, -1, "Bad Request: can't parse entities"));
        when(client.sendMessageChecked(eq(101L), anyString(), isNull(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            editor.sessionFor(101L).currentMessageId.set(9L);
            assertThat(editor.finalizeStream(101L, 9L, text)).isTrue();
            var parts = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(2)).sendMessageChecked(eq(101L), parts.capture(), isNull(), any(), any(), any(), anyBoolean(), any());
            assertThat(parts.getAllValues()).allMatch(part -> part.length() <= 4096);
            assertThat(String.join("", parts.getAllValues().stream()
                .map(part -> part.replaceFirst("^\\(\\d+/\\d+\\) ", "")).toList())).isEqualTo(text);
            verify(client, never()).editMessageChecked(anyLong(), anyLong(), anyString(), any(), anyBoolean(), any());
        } finally {
            editor.destroy();
        }
    }

    @Test
    void htmlSplitsByActualEscapeCost() {
        var plain = com.azhukov.agent.bot.formatting.MessageSplitter.splitHtmlSource("а".repeat(4097));
        assertThat(plain).hasSize(2);
        String text = "&<>🙂".repeat(410) + "tail";
        var escaped = com.azhukov.agent.bot.formatting.MessageSplitter.splitHtmlSource(text);
        assertThat(escaped).allMatch(part -> part.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .length() <= 4096);
        assertThat(String.join("", escaped.stream()
            .map(part -> part.replaceFirst("^\\(\\d+/\\d+\\) ", "")).toList())).isEqualTo(text);
    }

    @Test
    void longMarkdownBlockDeliversItsTailAndKeepsPlainFragmentsWithinLimit() {
        String text = "intro\n\n~~~\n\n" + "paragraph\n\n".repeat(530) + "TAIL_MARKER\n~~~\n\nafter";
        when(client.sendMessageChecked(eq(101L), anyString(), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            assertThat(editor.sendFormattedFinalMessage(101L, text)).contains(17L);
            var sent = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(2)).sendMessageChecked(
                eq(101L), sent.capture(), any(), any(), any(), isNull(), anyBoolean(), any());
            assertThat(sent.getAllValues()).allMatch(part -> part.length() <= 4096);
            assertThat(String.join("", sent.getAllValues())).contains("TAIL\\_MARKER");
            var originals = com.azhukov.agent.bot.formatting.MessageSplitter.splitMarkdown(text).stream()
                .map(part -> part.original().replaceFirst("^\\(\\d+/\\d+\\) ", "")).toList();
            assertThat(originals).allMatch(part -> part.length() <= 4096);
            assertThat(String.join("", originals)).isEqualTo(text);
        } finally {
            editor.destroy();
        }
    }

    @Test
    void markdownKeepsSmallCodeBlocksWholeAndClosesEachPartOfALargeBlock() {
        String fence = "```";
        String code = "item_name = 42\n".repeat(12);
        String shortBlock = fence + "python\n" + code + fence;
        String prefix = "a".repeat(2010) + "\n\n";
        String suffix = "\n\n" + "b".repeat(2300);
        var shortParts = com.azhukov.agent.bot.formatting.MessageSplitter.splitMarkdown(prefix + shortBlock + suffix);
        assertThat(shortParts).anyMatch(part -> part.formatted().contains(shortBlock));

        String longBlock = fence + "python\n" + code.repeat(32) + "TAIL_MARKER\n" + fence;
        String text = prefix + longBlock + suffix;
        var longParts = com.azhukov.agent.bot.formatting.MessageSplitter.splitMarkdown(text);
        assertThat(longParts).allMatch(part -> part.original().length() <= 4096 && part.formatted().length() <= 4096);
        assertThat(longParts).anyMatch(part -> part.formatted().contains("TAIL_MARKER\n" + fence));
        assertThat(longParts.stream().filter(part -> part.formatted().contains("item_name")))
            .allMatch(part -> part.formatted().contains(fence + "python\n"));
        assertThat(longParts.stream()
            .map(part -> part.original().replaceFirst("^\\(\\d+/\\d+\\) ", ""))
            .collect(java.util.stream.Collectors.joining())).isEqualTo(text);
    }

    @Test
    void oversizedToolSegmentIsSplitBeforeFinalDelivery() {
        when(client.sendMessageChecked(eq(101L), anyString(), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            var session = editor.sessionFor(101L);
            session.currentMessageId.set(42L);
            editor.onSegmentBreak(101L, 42L, "word ".repeat(1000), session);
            assertThat(editor.finalizeStream(101L, -1L, "final")).isTrue();

            var messages = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(3)).sendMessageChecked(
                eq(101L), messages.capture(), any(), any(), any(), isNull(), anyBoolean(), any());
            assertThat(messages.getAllValues()).allMatch(message -> message.length() <= 4096);
            assertThat(messages.getAllValues().getLast()).isEqualTo("final");
            verify(client).deleteMessage(101L, 42L);
        } finally {
            editor.destroy();
        }
    }

    @Test
    void cancellingWhileAConfirmedActionRunsPreventsFollowingActions() {
        var editor = editor(false, 0);
        var calls = new java.util.ArrayList<String>();
        try {
            editor.sessionFor(101L);
            editor.afterDeferredDelivery(101L, () -> {
                calls.add("started");
                editor.cancelDeferredDelivery(101L);
            });
            editor.afterDeferredDelivery(101L, () -> calls.add("stale"));

            editor.afterConfirmedDelivery(101L);

            assertThat(calls).containsExactly("started");
        } finally {
            editor.destroy();
        }
    }

    @Test
    @Tag("slow")
    void slowRetryDoesNotBlockAnotherDueRetry() throws Exception {
        var editor = editor(false, 0);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        when(client.sendMessageChecked(eq(101L), eq("first"), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenAnswer(call -> {
                firstStarted.countDown();
                if (!releaseFirst.await(8, SECONDS)) throw new IllegalStateException("first retry gate");
                return Optional.of(1L);
            });
        when(client.sendMessageChecked(eq(202L), eq("second"), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenAnswer(call -> {
                secondStarted.countDown();
                return Optional.of(2L);
            });
        try {
            editor.sendFormattedFinalMessage(101L, "first");
            var first = editor.sealDeferredDelivery(101L);
            editor.sendFormattedFinalMessage(202L, "second");
            var second = editor.sealDeferredDelivery(202L);
            assertThat(firstStarted.await(4, SECONDS)).isTrue();
            assertThat(secondStarted.await(2, SECONDS)).isTrue();
            assertThat(second.get(2, SECONDS)).isTrue();
            editor.cancelDeferredDelivery(101L);
            assertThat(first).isNotDone();
            releaseFirst.countDown();
            assertThat(first.get(2, SECONDS)).isFalse();
        } finally {
            releaseFirst.countDown();
            editor.destroy();
        }
    }

    @Test
    @Tag("slow")
    void successfulDeferredReplacementRemovesOldPreview() throws Exception {
        var editor = editor(false, 1);
        when(client.sendMessageChecked(eq(101L), eq("final"), any(), any(), any(), isNull(), anyBoolean(), any()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenReturn(Optional.of(17L));
        try {
            var session = editor.sessionFor(101L);
            session.streamStartTime = System.currentTimeMillis() - 5000;
            session.currentMessageId.set(42L);
            assertThat(editor.finalizeStream(101L, 42L, "final")).isFalse();
            assertThat(editor.sealDeferredDelivery(101L).get(5, SECONDS)).isTrue();
            editor.afterConfirmedDelivery(101L);
            editor.afterConfirmedDelivery(101L);
            verify(client).deleteMessage(101L, 42L);
        } finally {
            editor.destroy();
        }
    }
}
