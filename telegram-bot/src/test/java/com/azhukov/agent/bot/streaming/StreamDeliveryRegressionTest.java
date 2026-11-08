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

    @Test
    void ambiguousRichReceiptDoesNotSendAnotherFinal() {
        var me = mock(TelegramResponse.class);
        when(me.resultAsMap()).thenReturn(Map.of("supports_rich_messages", true));
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(me));
        when(client.callApi(eq("sendRichMessage"), anyMap()))
            .thenThrow(new TelegramApiException(-1, "receipt lost after acceptance"));
        var editor = editor(true, 0);
        try {
            editor.sessionFor(101L).useDraftStreaming = true;
            assertThat(editor.finalizeStream(101L, -1L, "final")).isFalse();
            assertThat(editor.hasUnconfirmedDelivery(101L)).isTrue();
            verify(client).callApi(eq("sendRichMessage"), anyMap());
            verify(client, never()).sendMessageChecked(eq(101L), anyString(), any(), any(), any(), any(), anyBoolean());
        } finally {
            editor.destroy();
        }
    }

    @Test
    @Tag("slow")
    void silentFinalLeavesEarlierDeferredSegmentQueued() throws Exception {
        when(client.sendMessageChecked(eq(101L), eq("earlier"), any(), any(), any(), isNull(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenReturn(Optional.of(1L));
        var editor = editor(false, 0);
        try {
            editor.sendFormattedFinalMessage(101L, "earlier");
            assertThat(editor.finalizeStream(101L, -1L, "<think>hidden</think>NO_REPLY")).isFalse();
            assertThat(editor.sealDeferredDelivery(101L).get(5, SECONDS)).isTrue();
            verify(client, times(2)).sendMessageChecked(
                eq(101L), eq("earlier"), any(), any(), any(), isNull(), anyBoolean());
            verifyNoMoreInteractions(client);
        } finally {
            editor.destroy();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 800})
    void markdownRejectionSendsOriginalFragmentsAsPlainText(int repeats) {
        String text = "file_name. ".repeat(repeats).stripTrailing();
        when(client.sendMessageChecked(eq(101L), anyString(), eq("MarkdownV2"), any(), any(), isNull(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(400, -1, "Bad Request: can't parse entities"));
        when(client.sendMessageChecked(eq(101L), anyString(), isNull(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            assertThat(editor.sendFormattedFinalMessage(101L, text)).contains(17L);
            var plain = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeastOnce()).sendMessageChecked(
                eq(101L), plain.capture(), isNull(), any(), any(), isNull(), anyBoolean());
            String joined = plain.getAllValues().stream()
                .map(part -> part.replaceFirst("^\\(\\d+/\\d+\\) ", ""))
                .collect(java.util.stream.Collectors.joining());
            assertThat(joined).isEqualTo(text);
        } finally {
            editor.destroy();
        }
    }

    @Test
    void longMarkdownBlockDeliversItsTailAndKeepsPlainFragmentsWithinLimit() {
        String text = "intro\n\n~~~\n\n" + "paragraph\n\n".repeat(530) + "TAIL_MARKER\n~~~\n\nafter";
        when(client.sendMessageChecked(eq(101L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            assertThat(editor.sendFormattedFinalMessage(101L, text)).contains(17L);
            var sent = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(2)).sendMessageChecked(
                eq(101L), sent.capture(), any(), any(), any(), isNull(), anyBoolean());
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
    void oversizedToolSegmentIsSplitBeforeFinalDelivery() {
        when(client.sendMessageChecked(eq(101L), anyString(), any(), any(), any(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(17L));
        var editor = editor(false, 0);
        try {
            var session = editor.sessionFor(101L);
            session.currentMessageId.set(42L);
            editor.onSegmentBreak(101L, 42L, "word ".repeat(1000), session);
            assertThat(editor.finalizeStream(101L, -1L, "final")).isTrue();

            var messages = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(client, atLeast(3)).sendMessageChecked(
                eq(101L), messages.capture(), any(), any(), any(), isNull(), anyBoolean());
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
        when(client.sendMessageChecked(eq(101L), eq("first"), any(), any(), any(), isNull(), anyBoolean()))
            .thenThrow(new TelegramClient.SendRefusalException(429, 1, "rate limit")).thenAnswer(call -> {
                firstStarted.countDown();
                if (!releaseFirst.await(8, SECONDS)) throw new IllegalStateException("first retry gate");
                return Optional.of(1L);
            });
        when(client.sendMessageChecked(eq(202L), eq("second"), any(), any(), any(), isNull(), anyBoolean()))
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
        when(client.sendMessageChecked(eq(101L), eq("final"), any(), any(), any(), isNull(), anyBoolean()))
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
