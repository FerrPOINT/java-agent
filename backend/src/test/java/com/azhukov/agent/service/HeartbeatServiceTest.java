package com.azhukov.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity tests for HeartbeatService (hermes_cli/heartbeat.py +
 * hermes_cli/loops.py): interval parsing, formatting, the state machine,
 * idle-coalescing, LOOP_COMPLETE detection and --times caps.
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
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
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
        assertThat(resumed.isDue(Instant.now().plusSeconds(10))).isFalse();

        assertThat(svc.clear(sid)).isTrue();
        assertThat(svc.get(sid)).isNull();
    }

    @Test
    void staleDueSnapshotCannotResurrectClearedHeartbeat() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        UUID sid = UUID.randomUUID();
        var snapshot = svc.set(sid, "old", 60);
        assertThat(svc.clear(sid)).isTrue();

        svc.tick(sid, snapshot, Instant.now().plusSeconds(120));

        assertThat(svc.get(sid)).isNull();
    }

    @Test
    void staleDueSnapshotCannotOverridePausedHeartbeat() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        UUID sid = UUID.randomUUID();
        var snapshot = svc.set(sid, "old", 60);
        svc.pause(sid);

        svc.tick(sid, snapshot, Instant.now().plusSeconds(120));

        assertThat(svc.get(sid).status()).isEqualTo("paused");
    }

    @Test
    void replacementDuringTurnSurvivesCompletionOfOldTurn() throws Exception {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        var runtime = org.mockito.Mockito.mock(AgentRuntimeService.class);
        var field = HeartbeatService.class.getDeclaredField("agentRuntimeService");
        field.setAccessible(true);
        field.set(svc, runtime);
        UUID sid = UUID.randomUUID();
        var old = svc.set(sid, "old", 60);
        org.mockito.Mockito.when(runtime.runHeartbeatTurn(org.mockito.ArgumentMatchers.eq(sid),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("default")))
            .thenAnswer(invocation -> {
                svc.set(sid, "replacement", 120);
                return "LOOP_COMPLETE";
            });

        svc.tick(sid, old, Instant.now().plusSeconds(120));

        assertThat(svc.get(sid).prompt()).isEqualTo("replacement");
        assertThat(svc.get(sid).fireCount()).isZero();
        assertThat(svc.peekLastFireResult(sid)).isNull();
    }

    @Test
    void resumePersistsActiveStateAndClearDuringTurnRemovesPersistedState() throws Exception {
        var repository = org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        var svc = new HeartbeatService(repository, new ProfileRuntimeRegistry(null));
        var entity = new com.azhukov.agent.persistence.entity.SessionEntity();
        UUID sid = UUID.randomUUID();
        org.mockito.Mockito.when(repository.findById(sid)).thenReturn(java.util.Optional.of(entity));
        svc.set(sid, "old", 60);
        svc.pause(sid);
        svc.resume(sid);
        assertThat(entity.getCliState().get("heartbeat")).contains("active");

        var runtime = org.mockito.Mockito.mock(AgentRuntimeService.class);
        var runtimeField = HeartbeatService.class.getDeclaredField("agentRuntimeService");
        runtimeField.setAccessible(true);
        runtimeField.set(svc, runtime);
        org.mockito.Mockito.when(runtime.runHeartbeatTurn(org.mockito.ArgumentMatchers.eq(sid),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("default")))
            .thenAnswer(invocation -> { svc.clear(sid); return "done"; });
        var active = svc.get(sid);
        svc.tick(sid, active, Instant.now().plusSeconds(120));

        assertThat(svc.get(sid)).isNull();
        assertThat(entity.getCliState()).doesNotContainKey("heartbeat");
    }

    @Test
    void promptContainingSeparatorSurvivesPersistAndRestore() throws Exception {
        UUID sid = UUID.randomUUID();
        String prompt = "first\u0001second";
        var entity = new com.azhukov.agent.persistence.entity.SessionEntity();
        var repository = org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        org.mockito.Mockito.when(repository.findById(sid)).thenReturn(java.util.Optional.of(entity));
        var original = new HeartbeatService(repository, new ProfileRuntimeRegistry(null));
        original.set(sid, prompt, 60);

        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
            .thenReturn(java.util.List.of(java.util.Map.of("session_id", sid, "profile", "default", "state_value", entity.getCliState().get("heartbeat"))));
        var restored = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        var jdbcField = HeartbeatService.class.getDeclaredField("jdbcTemplate");
        jdbcField.setAccessible(true);
        jdbcField.set(restored, jdbc);
        restored.restorePersisted();

        assertThat(restored.get(sid)).isNotNull();
        assertThat(restored.get(sid).prompt()).isEqualTo(prompt);
    }

    @Test
    @DisplayName("fire prompt carries the Hermes HEARTBEAT_PROMPT_TEMPLATE contract")
    void firePromptTemplate() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        HeartbeatService.HeartbeatState st = new HeartbeatService.HeartbeatState(
            "Check the deployment", 600, "active", Instant.now(), null, 0, 0, "default");
        String prompt = svc.buildFirePrompt(st);
        assertThat(prompt).startsWith("[Heartbeat — recurring instruction, fires every 10m]");
        assertThat(prompt).contains("Check the deployment");
        assertThat(prompt).contains("do not invent work");
    }

    // ── /loop semantics (Hermes loops.py) ──

    @Test
    @DisplayName("responseSignalsComplete: marker on its own line, trailing .! tolerated")
    void loopCompleteDetection() {
        assertThat(HeartbeatService.responseSignalsComplete("All done.\nLOOP_COMPLETE")).isTrue();
        assertThat(HeartbeatService.responseSignalsComplete("LOOP_COMPLETE.")).isTrue();
        assertThat(HeartbeatService.responseSignalsComplete("  LOOP_COMPLETE  ")).isTrue();
        // NOT on its own line / embedded:
        assertThat(HeartbeatService.responseSignalsComplete("The LOOP_COMPLETE thing is done")).isFalse();
        // Hermes (?i): case-insensitive — lowercase marker on its own line DOES match
        assertThat(HeartbeatService.responseSignalsComplete("loop_complete")).isTrue();
        assertThat(HeartbeatService.responseSignalsComplete("")).isFalse();
        assertThat(HeartbeatService.responseSignalsComplete(null)).isFalse();
    }

    @Test
    @DisplayName("--times cap: isDue false once fireCount reaches maxTicks")
    void timesCap() {
        HeartbeatService.HeartbeatState st = new HeartbeatService.HeartbeatState(
            "Watch CI", 60, "active", Instant.now().minusSeconds(500), null, 3, 3, "default");
        assertThat(st.fireCount()).isEqualTo(3);
        assertThat(st.maxTicks()).isEqualTo(3);
        assertThat(st.isDue(Instant.now())).isFalse();   // cap reached
    }

    @Test
    @DisplayName("tick(): busy session coalesces — anchor NOT advanced, no fire")
    void busySessionCoalesces() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null)) {
            @Override
            boolean isBusy(UUID sessionId) { return true; }
        };
        UUID sid = UUID.randomUUID();
        HeartbeatService.HeartbeatState st = svc.set(sid, "check", 60);
        Instant dueAt = Instant.now().plusSeconds(120);

        svc.tick(sid, st, dueAt);

        HeartbeatService.HeartbeatState after = svc.get(sid);
        assertThat(after.fireCount()).isZero();          // did not fire
        assertThat(after.lastFiredAt()).isNull();        // anchor unchanged → will fire when idle
    }

    @Test
    @DisplayName("tick(): idle session fires exactly once (anchor advanced)")
    void idleSessionFires() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null)) {
            @Override
            boolean isBusy(UUID sessionId) { return false; }
        };
        UUID sid = UUID.randomUUID();
        HeartbeatService.HeartbeatState st = svc.set(sid, "check", 60);
        Instant dueAt = Instant.now().plusSeconds(120);

        svc.tick(sid, st, dueAt);

        HeartbeatService.HeartbeatState after = svc.get(sid);
        assertThat(after.fireCount()).isEqualTo(1);
        assertThat(after.lastFiredAt()).isNotNull();
    }
    @Test
    @DisplayName("persistence: set/clear write through to session_cli_state")
    void persistenceWriteThrough() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null));
        UUID sid = UUID.randomUUID();
        svc.set(sid, "Check CI", 120, 3);
        assertThat(svc.get(sid)).isNotNull();
        assertThat(svc.get(sid).maxTicks()).isEqualTo(3);
    }

    @Test
    @DisplayName("restore: persisted active heartbeat is reloaded")
    void restorePersistedHeartbeat() {
        HeartbeatService svc = new HeartbeatService(defaultSessions(), new ProfileRuntimeRegistry(null)) {
            @Override
            boolean isBusy(UUID sessionId) { return false; }
        };
        UUID sid = UUID.randomUUID();
        svc.set(sid, "Watch deploy", 60);
        svc.restorePersisted();
        assertThat(svc.get(sid)).isNotNull();
    }

    private com.azhukov.agent.persistence.repository.SessionRepository defaultSessions() {
        var repository = org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        org.mockito.Mockito.when(repository.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
            .thenAnswer(invocation -> java.util.Optional.of(new com.azhukov.agent.persistence.entity.SessionEntity()));
        return repository;
    }
}
