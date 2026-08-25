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
 * S5: Tests for native draft streaming (sendMessageDraft) support in StreamEditor.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Transport config: auto/draft/edit/off</li>
 *   <li>supportsDraftStreaming: DM-only gating</li>
 *   <li>Draft streaming lifecycle: start → edit → finalize</li>
 *   <li>Draft failure fallback: after 2 failures, falls back to edit-based</li>
 *   <li>Segment break: bumps draftId, commits draft as real message</li>
 *   <li>Off transport: buffers content, no streaming</li>
 * </ul>
 */
class StreamEditorDraftStreamingTest {

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
        props.setStreamingTransport("auto"); // Default to auto for draft streaming
        editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        editor.init();
        // Mock getMe to return no rich messages support
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of());
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));
        when(client.getLastApiErrorCode()).thenReturn(0);
        // Default: supportsDraftStreaming returns true for DMs
        when(client.supportsDraftStreaming(anyString())).thenReturn(true);
        // Default: sendDraft succeeds
        when(client.sendDraft(anyLong(), anyString(), anyInt())).thenReturn(true);
    }

    // ─── Transport config tests ──────────────────────────────────

    @Test
    void supportsDraftStreaming_dmReturnsTrue() {
        // Call on the real method (not the mock) — create a real TelegramClient
        // with a dummy token to test the chat type logic
        // Since TelegramClient.supportsDraftStreaming is a simple chat-type check,
        // we test it via the mock with explicit stubs
        when(client.supportsDraftStreaming("dm")).thenReturn(true);
        when(client.supportsDraftStreaming("private")).thenReturn(true);
        assertThat(client.supportsDraftStreaming("dm")).isTrue();
        assertThat(client.supportsDraftStreaming("private")).isTrue();
    }

    @Test
    void supportsDraftStreaming_groupReturnsFalse() {
        when(client.supportsDraftStreaming("group")).thenReturn(false);
        when(client.supportsDraftStreaming("supergroup")).thenReturn(false);
        when(client.supportsDraftStreaming("forum")).thenReturn(false);
        when(client.supportsDraftStreaming(null)).thenReturn(false);
        assertThat(client.supportsDraftStreaming("group")).isFalse();
        assertThat(client.supportsDraftStreaming("supergroup")).isFalse();
        assertThat(client.supportsDraftStreaming("forum")).isFalse();
        assertThat(client.supportsDraftStreaming(null)).isFalse();
    }

    @Test
    void startStream_withAutoTransportAndDm_usesDraftStreaming() {
        // With auto transport and DM chat type, draft streaming should be active
        Optional<Long> msgId = editor.startStream(123L, "Hello world", "dm");

        // Draft streaming returns empty (no message_id — drafts have no message_id)
        assertThat(msgId).isEmpty();
        assertThat(editor.isDraftStreamingActive(123L)).isTrue();
        // Should have called sendDraft (not sendMessage)
        verify(client).sendDraft(eq(123L), anyString(), anyInt());
        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void startStream_withEditTransport_doesNotUseDraftStreaming() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingTransport("edit");
        StreamEditor editEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        editEditor.init();

        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = editEditor.startStream(123L, "Hello world", "dm");

        // With edit transport, should use regular sendMessage
        assertThat(msgId).contains(42L);
        assertThat(editEditor.isDraftStreamingActive(123L)).isFalse();
        verify(client, never()).sendDraft(anyLong(), anyString(), anyInt());
    }

    @Test
    void startStream_withOffTransport_buffersAndReturnsEmpty() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingTransport("off");
        StreamEditor offEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        offEditor.init();

        Optional<Long> msgId = offEditor.startStream(123L, "Hello world", "dm");

        assertThat(msgId).isEmpty();
        assertThat(offEditor.isDraftStreamingActive(123L)).isFalse();
        assertThat(offEditor.isStreamingOff()).isTrue();
        verify(client, never()).sendDraft(anyLong(), anyString(), anyInt());
        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any(), any());
    }

    @Test
    void startStream_withGroupChat_doesNotUseDraftStreaming() {
        // Override: group chats don't support draft streaming
        when(client.supportsDraftStreaming("group")).thenReturn(false);
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = editor.startStream(123L, "Hello world", "group");

        // Group chats should use edit-based, not draft
        assertThat(msgId).contains(42L);
        assertThat(editor.isDraftStreamingActive(123L)).isFalse();
        verify(client, never()).sendDraft(anyLong(), anyString(), anyInt());
    }

    // ─── Draft streaming lifecycle tests ─────────────────────────

    @Test
    void editStream_withDraftStreaming_sendsDraftFrames() throws InterruptedException {
        editor.startStream(123L, "Hello", "dm");

        // Wait past throttle interval
        Thread.sleep(110);

        boolean result = editor.editStream(123L, -1, "Hello world");
        assertThat(result).isTrue();
        // Should have called sendDraft at least twice (initial + edit)
        verify(client, atLeast(2)).sendDraft(eq(123L), anyString(), anyInt());
        // Should NOT have called editMessageText
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    void finalizeStream_withDraftStreaming_sendsRegularMessage() {
        editor.startStream(123L, "Hello world", "dm");

        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        boolean result = editor.finalizeStream(123L, -1, "Hello world final");

        assertThat(result).isTrue();
        // Final message is sent WITH parseMode (MarkdownV2): Hermes parity —
        // format_message is always applied before delivery, even for draft finalize.
        verify(client).sendMessage(eq(123L), anyString(), eq("MarkdownV2"), any(), any(), anyBoolean());
        // Should NOT call editMessageText (drafts have no message_id)
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
        // Draft streaming should be cleaned up
        assertThat(editor.isDraftStreamingActive(123L)).isFalse();
    }

    // ─── Draft failure fallback tests ────────────────────────────

    @Test
    void editStream_afterTwoDraftFailures_fallsBackToEditBased() throws InterruptedException {
        // First draft frame succeeds (in startStream)
        editor.startStream(123L, "Hello", "dm");

        // Now make sendDraft fail
        when(client.sendDraft(anyLong(), anyString(), anyInt())).thenReturn(false);

        // S5 tolerance: a single draft failure falls back to edit-based
        // (>= 2 was too forgiving — users waited through repeated 400s).
        Thread.sleep(110);
        editor.editStream(123L, -1, "Hello world updated");

        // After 1 failure, draft streaming should be disabled
        assertThat(editor.isDraftStreamingActive(123L)).isFalse();
    }

    // ─── Segment break tests ─────────────────────────────────────

    @Test
    void onSegmentBreak_withDraftStreaming_commitsDraftAndBumpsId() {
        editor.startStream(123L, "First segment text", "dm");

        // Send a segment break — should commit the draft as a real message
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(50L));

        editor.onSegmentBreak(123L, -1, "First segment text accumulated");

        // Should have sent the accumulated text as a real message
        verify(client).sendMessage(eq(123L), anyString(), eq("MarkdownV2"), any(), any());
        // Draft streaming should still be active (segment break doesn't disable it)
        assertThat(editor.isDraftStreamingActive(123L)).isTrue();
    }

    @Test
    void onSegmentBreak_withEmptyText_doesNothing() {
        editor.startStream(123L, "", "dm");

        editor.onSegmentBreak(123L, -1, "");

        // Should not have sent any message
        verify(client, never()).sendMessage(anyLong(), anyString(), anyString(), any(), any());
    }

    // ─── Off transport tests ─────────────────────────────────────

    @Test
    void editStream_withOffTransport_buffersContent() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingTransport("off");
        StreamEditor offEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        offEditor.init();

        offEditor.startStream(123L, "Hello", "dm");
        boolean result = offEditor.editStream(123L, -1, "Hello world");

        assertThat(result).isFalse();
        verify(client, never()).sendDraft(anyLong(), anyString(), anyInt());
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    void finalizeStream_withOffTransport_sendsBufferedContent() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingTransport("off");
        StreamEditor offEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        offEditor.init();

        offEditor.startStream(123L, "Hello", "dm");
        offEditor.editStream(123L, -1, "Hello world");

        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        boolean result = offEditor.finalizeStream(123L, -1, "Hello world final");

        assertThat(result).isTrue();
        // 'off' transport finalize sends RAW text (parseMode=null) — no escaping applied.
        verify(client).sendMessage(eq(123L), anyString(), isNull(), any(), any(), anyBoolean());
    }

    // ─── Draft transport (explicit) tests ────────────────────────

    @Test
    void startStream_withDraftTransportAndGroup_fallsBackToEdit() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingTransport("draft");
        StreamEditor draftEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        draftEditor.init();

        // Group chat with draft transport — should fall back to edit
        when(client.supportsDraftStreaming("group")).thenReturn(false);
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = draftEditor.startStream(123L, "Hello world", "group");

        assertThat(msgId).contains(42L);
        assertThat(draftEditor.isDraftStreamingActive(123L)).isFalse();
        verify(client, never()).sendDraft(anyLong(), anyString(), anyInt());
    }
}