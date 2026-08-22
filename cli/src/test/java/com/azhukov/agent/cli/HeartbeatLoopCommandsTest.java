package com.azhukov.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interval parsing parity for /heartbeat and /loop CLI commands
 * (Hermes hermes_cli/loops.py _INTERVAL_TOKEN_RE: compound 1h30m allowed).
 */
class HeartbeatLoopCommandsTest {

    @Test
    @DisplayName("compound interval tokens parse (30s / 5m / 2h / 1h30m)")
    void compoundIntervals() {
        assertThat(HeartbeatLoopCommands.parseIntervalToken("30s")).isEqualTo(30);
        assertThat(HeartbeatLoopCommands.parseIntervalToken("5m")).isEqualTo(300);
        assertThat(HeartbeatLoopCommands.parseIntervalToken("2h")).isEqualTo(7200);
        assertThat(HeartbeatLoopCommands.parseIntervalToken("1h30m")).isEqualTo(5400);
        assertThat(HeartbeatLoopCommands.parseIntervalToken("1h30m15s")).isEqualTo(5415);
    }

    @Test
    @DisplayName("rejects garbage and bare numbers")
    void rejectsGarbage() {
        assertThat(HeartbeatLoopCommands.parseIntervalToken("soon")).isNull();
        assertThat(HeartbeatLoopCommands.parseIntervalToken("10")).isNull();   // no unit
        assertThat(HeartbeatLoopCommands.parseIntervalToken("")).isNull();
        assertThat(HeartbeatLoopCommands.parseIntervalToken(null)).isNull();
        assertThat(HeartbeatLoopCommands.parseIntervalToken("m5")).isNull();   // unit first
    }

    @Test
    @DisplayName("zero-only tokens are rejected (0s is not an interval)")
    void rejectsZero() {
        assertThat(HeartbeatLoopCommands.parseIntervalToken("0s")).isNull();
        assertThat(HeartbeatLoopCommands.parseIntervalToken("0m0s")).isNull();
    }
}
