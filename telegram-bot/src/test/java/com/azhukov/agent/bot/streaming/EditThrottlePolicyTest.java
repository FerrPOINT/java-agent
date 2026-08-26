package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EditThrottlePolicy} — the flood-backoff / throttle
 * policy extracted from StreamEditor.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Base interval (no backoff → returns configured minimum)</li>
 *   <li>Interval doubling on flood</li>
 *   <li>Interval cap at MAX_INTERVAL_MS</li>
 *   <li>Interval reset via session resetForNewStream</li>
 *   <li>429 detection — increment flood strikes</li>
 *   <li>Backoff state — interval increased after 429</li>
 *   <li>Consecutive failures — streaming disabled after MAX_FLOOD_STRIKES</li>
 *   <li>Threshold — FLOOD_WARN_THRESHOLD triggers warning (3 strikes)</li>
 *   <li>400 error — truncate and retry, no flood strike increment</li>
 *   <li>400 retry success — resets flood strikes</li>
 * </ul>
 */
class EditThrottlePolicyTest {

    private static final long MIN_INTERVAL = 500L;

    private TelegramClient client;
    private StreamSession session;
    private Function<String, String> identityFormatter;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        session = new StreamSession();
        session.resetForNewStream();
        identityFormatter = Function.identity();
    }

    // ─── getEffectiveInterval ──────────────────────────────────────

    @Test
    void getEffectiveInterval_returnsMinInterval_whenNoBackoff() {
        // Fresh session: editInterval is 0, should return the configured minimum
        assertThat(EditThrottlePolicy.getEffectiveInterval(session, MIN_INTERVAL))
            .isEqualTo(MIN_INTERVAL);
    }

    @Test
    void getEffectiveInterval_returnsBackoffInterval_whenSet() {
        // After backoff, editInterval is non-zero — should return it, not min
        session.editInterval.set(2000L);
        assertThat(EditThrottlePolicy.getEffectiveInterval(session, MIN_INTERVAL))
            .isEqualTo(2000L);
    }

    // ─── increaseInterval ──────────────────────────────────────────

    @Test
    void increaseInterval_doublesFromMinOnFirstFlood() {
        // First flood: interval goes from 0 (→min) to min×2
        EditThrottlePolicy.increaseInterval(session, MIN_INTERVAL);
        assertThat(session.editInterval.get()).isEqualTo(MIN_INTERVAL * 2);
    }

    @Test
    void increaseInterval_doublesExponentially() {
        // Pre-set interval and verify doubling
        session.editInterval.set(1000L);
        EditThrottlePolicy.increaseInterval(session, MIN_INTERVAL);
        assertThat(session.editInterval.get()).isEqualTo(2000L);

        EditThrottlePolicy.increaseInterval(session, MIN_INTERVAL);
        assertThat(session.editInterval.get()).isEqualTo(4000L);
    }

    @Test
    void increaseInterval_capsAtMaxInterval() {
        // Set interval close to cap and verify it doesn't exceed MAX_INTERVAL_MS
        session.editInterval.set(8000L);
        EditThrottlePolicy.increaseInterval(session, MIN_INTERVAL);
        assertThat(session.editInterval.get())
            .isEqualTo(EditThrottlePolicy.MAX_INTERVAL_MS)
            .isEqualTo(10000L);

        // Another increase should stay at cap
        EditThrottlePolicy.increaseInterval(session, MIN_INTERVAL);
        assertThat(session.editInterval.get()).isEqualTo(10000L);
    }

    // ─── reset ─────────────────────────────────────────────────────

    @Test
    void resetForNewStream_clearsBackoffState() {
        // Apply backoff, then reset, verify interval and strikes cleared
        session.editInterval.set(4000L);
        session.floodStrikes.set(2);
        session.streamingDisabled = true;

        session.resetForNewStream();

        assertThat(session.editInterval.get()).isZero();
        assertThat(session.floodStrikes.get()).isZero();
        assertThat(session.streamingDisabled).isFalse();
        assertThat(EditThrottlePolicy.getEffectiveInterval(session, MIN_INTERVAL))
            .isEqualTo(MIN_INTERVAL);
    }

    // ─── handleEditFailure: 429 detection ──────────────────────────

    @Test
    void handleEditFailure_429_incrementsFloodStrikes() {
        // 429 should increment flood strikes and increase interval
        boolean result = EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "some text", session, 429,
            MIN_INTERVAL, false, 0, identityFormatter);

        assertThat(result).isFalse();
        assertThat(session.floodStrikes.get()).isEqualTo(1);
        // Interval should have doubled from min
        assertThat(session.editInterval.get()).isEqualTo(MIN_INTERVAL * 2);
    }

    @Test
    void handleEditFailure_429_increasesInterval() {
        // Verify interval is increased after a 429
        long before = session.editInterval.get();
        EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text", session, 429,
            MIN_INTERVAL, false, 0, identityFormatter);

        long after = session.editInterval.get();
        assertThat(after).isGreaterThan(before);
        assertThat(after).isEqualTo(MIN_INTERVAL * 2);
    }

    // ─── handleEditFailure: consecutive failures / threshold ───────

    @Test
    void handleEditFailure_consecutive429_disablesStreamingAfterMaxStrikes() {
        // After MAX_FLOOD_STRIKES consecutive 429s, streaming should be disabled
        // and flood fallback buffer initialized
        for (int i = 0; i < EditThrottlePolicy.MAX_FLOOD_STRIKES; i++) {
            EditThrottlePolicy.handleEditFailure(
                client, 123L, 42L, "text " + i, session, 429,
                MIN_INTERVAL, false, 0, identityFormatter);
        }

        assertThat(session.floodStrikes.get()).isEqualTo(EditThrottlePolicy.MAX_FLOOD_STRIKES);
        assertThat(session.streamingDisabled).isTrue();
        // Flood fallback buffer should contain formatted content from the last failure
        assertThat(session.floodFallbackBuffer.length()).isGreaterThan(0);
    }

    @Test
    void handleEditFailure_thresholdTriggersAtWarnThreshold() {
        // FLOOD_WARN_THRESHOLD == 3, same as MAX_FLOOD_STRIKES.
        // After 2 strikes, streaming is NOT yet disabled.
        for (int i = 0; i < 2; i++) {
            EditThrottlePolicy.handleEditFailure(
                client, 123L, 42L, "text " + i, session, 429,
                MIN_INTERVAL, false, 0, identityFormatter);
        }

        assertThat(session.floodStrikes.get()).isEqualTo(2);
        assertThat(session.streamingDisabled).isFalse();

        // Third strike hits both WARN_THRESHOLD and MAX_FLOOD_STRIKES
        EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text 2", session, 429,
            MIN_INTERVAL, false, 0, identityFormatter);

        assertThat(session.floodStrikes.get()).isEqualTo(3);
        assertThat(session.streamingDisabled).isTrue();
    }

    @Test
    void handleEditFailure_twoStrikesDoNotDisable() {
        // Only 2 strikes — streaming should still be active
        EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text 1", session, 429,
            MIN_INTERVAL, false, 0, identityFormatter);
        EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text 2", session, 429,
            MIN_INTERVAL, false, 0, identityFormatter);

        assertThat(session.streamingDisabled).isFalse();
        assertThat(session.floodFallbackBuffer.length()).isZero();
    }

    // ─── handleEditFailure: 400 truncation retry ───────────────────

    @Test
    void handleEditFailure_400_truncatesAndRetries_success() {
        // 400: should truncate and retry, NOT increment flood strikes
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        String longText = "x".repeat(5000);
        boolean result = EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, longText, session, 400,
            MIN_INTERVAL, false, 4000, identityFormatter);

        assertThat(result).isTrue();
        assertThat(session.floodStrikes.get()).isZero(); // 400 doesn't increment strikes
        // Verify the retry was called with truncated text
        verify(client).editMessageText(eq(123L), eq(42L), anyString(), eq(null), eq(false));
    }

    @Test
    void handleEditFailure_400_truncatesAndRetries_failure() {
        // 400: retry returns false — should return false, no flood strikes
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(false);

        boolean result = EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "too long text", session, 400,
            MIN_INTERVAL, false, 4000, identityFormatter);

        assertThat(result).isFalse();
        assertThat(session.floodStrikes.get()).isZero();
    }

    @Test
    void handleEditFailure_400_retryGets429_returnsFalse() {
        // 400 retry gets a 429 — should return false without throwing
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenThrow(new TelegramApiException(429, "Too Many Requests"));

        boolean result = EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text", session, 400,
            MIN_INTERVAL, false, 4000, identityFormatter);

        assertThat(result).isFalse();
        // 400 path doesn't increment strikes even when retry gets 429
        assertThat(session.floodStrikes.get()).isZero();
    }

    @Test
    void handleEditFailure_400_resetsStrikesOnRetrySuccess() {
        // Pre-set some flood strikes, then a 400 retry succeeds — strikes should reset
        session.floodStrikes.set(2);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), anyBoolean()))
            .thenReturn(true);

        EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text", session, 400,
            MIN_INTERVAL, false, 4000, identityFormatter);

        assertThat(session.floodStrikes.get()).isZero();
    }

    // ─── handleEditFailure: flood fallback buffer formatting ───────

    @Test
    void handleEditFailure_floodBufferUsesFormatter() {
        // When streaming is disabled (max strikes), the fallback buffer should
        // contain the formatted version of the text
        Function<String, String> upperCase = s -> "[" + s + "]";

        for (int i = 0; i < EditThrottlePolicy.MAX_FLOOD_STRIKES; i++) {
            EditThrottlePolicy.handleEditFailure(
                client, 123L, 42L, "text " + i, session, 429,
                MIN_INTERVAL, false, 0, upperCase);
        }

        // The last failure's text should be formatted in the buffer
        assertThat(session.floodFallbackBuffer.toString()).contains("[text 2]");
    }

    // ─── handleEditFailure: non-400/non-429 error code ─────────────

    @Test
    void handleEditFailure_otherErrorCode_incrementsStrikes() {
        // Unknown error code should be treated like a flood (increment strikes)
        boolean result = EditThrottlePolicy.handleEditFailure(
            client, 123L, 42L, "text", session, 500,
            MIN_INTERVAL, false, 0, identityFormatter);

        assertThat(result).isFalse();
        assertThat(session.floodStrikes.get()).isEqualTo(1);
    }
}