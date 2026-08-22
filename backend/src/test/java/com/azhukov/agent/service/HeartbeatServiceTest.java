package com.azhukov.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity tests for HeartbeatService (hermes_cli/heartbeat.py):
 * interval parsing, formatting, the state machine and due-fire logic.
 */
class HeartbeatServiceTest {

    @Test
    @DisplayName("parseInterval mirrors Hermes _INTERVAL_RE: every/units/fractions")
    void parseIntervalParity() {
        assertThat(HeartbeatService.parseInterval("10m")).isEqualTo(600);
        assertThat(HeartbeatService.parseInterval("every 2h")).isEqualTo(7200);
        assertThat(HeartbeatService.parseInterval("every 90 minutes")).isEqualTo(5400);
        assertThat(HeartbeatService.parseInterval("45s")).isEqualTo(-1);       // below MIN → -1 (Hermes contract)
        assertThat(HeartbeatService.parseInterval("nope")).isNull();
        assertThat(HeartbeatService.parseInterval("")).isNull();
        assertThat(HeartbeatService.parseInterval(null)).isNull();
    }

    @Test
    @DisplayName("below-minimum intervals return -1 (distinguishable from not-an-interval)")
    void belowMinimumIsMinusOne() {
        assertThat(HeartbeatService.parseInterval("30s")).isEqualTo(-1);
        assertThat(HeartbeatService.parseInterval("every 10 seconds")).isEqualTo(-1);
    }

    @Test
    @DisplayName("formatInterval mirrors Hermes: 600→10m, 7200→2h, 86400→1d")
    void formatIntervalParity() {
        assertThat(HeartbeatService.formatInterval(600)).isEqualTo("10m");
        assertThat(HeartbeatService.formatInterval(7200)).isEqualTo("2h");
        assertThat(HeartbeatService.formatInterval(86400)).isEqualTo("1d");
        assertThat(HeartbeatService.formatInterval(45)).isEqualTo("45s");
    }

    @Test
    @DisplayName("state machine: set → due after interval → pause blocks → resume re-anchors")
    void stateMachine() {
        HeartbeatService svc = new HeartbeatService();
        UUID sid = UUID.randomUUID();
        HeartbeatService.HeartbeatState st = svc.set(sid, "Check CI", 60);

        assertThat(st.status()).isEqualTo("active");
        assertThat(st.isDue(Instant.now())).isFalse();
        assertThat(st.isDue(Instant.now().plusSeconds(61))).isTrue();

        HeartbeatService.HeartbeatState paused = svc.pause(sid);
        assertThat(paused.status()).isEqualTo("paused");
        assertThat(paused.isDue(Instant.now().plusSeconds(600))).isFalse();

        HeartbeatService.HeartbeatState resumed = svc.resume(sid);
        assertThat(resumed.status()).isEqualTo("active");
        // resume re-anchors: not due immediately after resuming
        assertThat(resumed.isDue(Instant.now().plusSeconds(10))).isFalse();

        assertThat(svc.clear(sid)).isTrue();
        assertThat(svc.get(sid)).isNull();
    }

    @Test
    @DisplayName("fire prompt carries the Hermes HEARTBEAT_PROMPT_TEMPLATE contract")
    void firePromptTemplate() {
        HeartbeatService svc = new HeartbeatService();
        HeartbeatService.HeartbeatState st = new HeartbeatService.HeartbeatState(
            "Check the deployment", 600, "active", Instant.now(), null, 0);
        String prompt = svc.buildFirePrompt(st);
        assertThat(prompt).startsWith("[Heartbeat — recurring instruction, fires every 10m]");
        assertThat(prompt).contains("Check the deployment");
        assertThat(prompt).contains("do not invent work");
    }
}
