package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-R5 (Hermes conversation_loop.py:1033-1075, 7568-7577, 7640-7712):
 * nudge texts byte-for-byte, jittered backoff math, deterministic-empty guard.
 */
class ResponseRecoveryParityTest {

    @Test
    void lengthNudgesMatchHermesByteForByte() {
        // _LENGTH_CONTINUATION_OUTPUT_LIMIT
        assertThat(ResponseRecoveryPolicy.LENGTH_NUDGE)
            .isEqualTo("[System: Your previous response was truncated by the output "
                + "length limit. Continue exactly where you left off. Do not "
                + "restart or repeat prior text. Finish the answer directly.]");
        // _LENGTH_CONTINUATION_NETWORK_STUB
        assertThat(ResponseRecoveryPolicy.LENGTH_NETWORK_STUB_NUDGE)
            .isEqualTo("[System: The previous response was cut off by a "
                + "network error mid-stream. Continue exactly where "
                + "you left off. Do not restart or repeat prior text. "
                + "Finish the answer directly.]");
    }

    @Test
    void droppedToolsNudgeListsMaxThreeToolsAndInstructsChunking() {
        String nudge = ResponseRecoveryPolicy.lengthDroppedToolsNudge(
            java.util.List.of("patch", "write_file", "terminal", "browser"));
        assertThat(nudge).startsWith("[System: Your previous tool call (patch, write_file, terminal)");
        assertThat(nudge).contains("under ~8K");
        assertThat(nudge).endsWith("]");
        // null-safe
        assertThat(ResponseRecoveryPolicy.lengthDroppedToolsNudge(null))
            .startsWith("[System: Your previous tool call ()");
    }

    @Test
    void emptyAfterToolsNudgeMatchesHermes() {
        // _EMPTY_TOOL_RESPONSE_NUDGE
        assertThat(ResponseRecoveryPolicy.EMPTY_AFTER_TOOLS_NUDGE)
            .isEqualTo("You just executed tool calls but returned an "
                + "empty response. Please process the tool "
                + "results above and continue with the task.");
    }

    @Test
    void jitteredBackoffIsExponentialCappedWithBoundedJitter() {
        // base 5000: attempt1=5s, attempt2=10s, attempt3=20s, cap 60000
        for (int i = 0; i < 200; i++) {
            long a1 = ResponseRecoveryPolicy.jitteredBackoffMs(1, 5000, 60000);
            assertThat(a1).isBetween(3750L, 6250L);      // 5000 * [0.75..1.25]
            long a2 = ResponseRecoveryPolicy.jitteredBackoffMs(2, 5000, 60000);
            assertThat(a2).isBetween(7500L, 12500L);     // 10000 * [0.75..1.25]
            long a5 = ResponseRecoveryPolicy.jitteredBackoffMs(5, 5000, 60000);
            assertThat(a5).isBetween(45000L, 75000L);    // capped 60000 * [0.75..1.25]
        }
    }

    @Test
    void deterministicEmptyRequiresTwoZeroOutputAttemptsSameSignature() {
        EmptyResponseGuard guard = new EmptyResponseGuard();
        assertThat(guard.deterministicEmpty()).isFalse(); // empty → fail open

        guard.recordEmptyAttempt("m", "p", "STOP", null); // missing usage
        guard.recordEmptyAttempt("m", "p", "STOP", null);
        assertThat(guard.deterministicEmpty()).isFalse(); // fail open without usage

        guard.reset();
        guard.recordEmptyAttempt("m", "p", "STOP", 0L);
        guard.recordEmptyAttempt("m", "p", "STOP", 5L); // some real output
        assertThat(guard.deterministicEmpty()).isFalse();

        guard.reset();
        guard.recordEmptyAttempt("m", "p", "STOP", 0L);
        guard.recordEmptyAttempt("m", "p", "STOP", 0L);
        assertThat(guard.deterministicEmpty()).isTrue();  // the real case

        guard.reset();
        guard.recordEmptyAttempt("m1", "p", "STOP", 0L);
        guard.recordEmptyAttempt("m2", "p", "STOP", 0L); // signature changed
        assertThat(guard.deterministicEmpty()).isFalse();
    }
}
