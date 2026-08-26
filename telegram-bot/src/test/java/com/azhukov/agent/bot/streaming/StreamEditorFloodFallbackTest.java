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
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        boolean result = editor.finalizeStream(123L, 42L, "Hello world final buffered content");

        assertThat(result).isTrue();
        // Flood fallback now sends formatted text (parseMode=MarkdownV2) — Hermes parity.
        // Anchor on the buffered content to exclude the earlier delayed-start message.
        verify(client).sendMessage(eq(123L), contains("buffered content"), eq("MarkdownV2"), any(), any(), anyBoolean());
        // Should have deleted the old streaming message
        verify(client).deleteMessage(123L, 42L);
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