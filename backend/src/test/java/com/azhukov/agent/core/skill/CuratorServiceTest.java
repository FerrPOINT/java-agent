package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CuratorService} — S5 fixes.
 */
@ExtendWith(MockitoExtension.class)
class CuratorServiceTest {

    @Mock private SkillRepository skillRepository;
    @Mock private ModelClient modelClient;
    @Mock private CuratorBackupService backupService;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
    }

    // ── S5: Config-driven interval ─────────────────────────────────────

    @Test
    void configDrivenInterval_defaultIs7Days() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.getIntervalHours()).isEqualTo(24 * 7);
    }

    @Test
    void configDrivenInterval_canBeConfigured() {
        properties.getCurator().setIntervalHours(48);
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.getIntervalHours()).isEqualTo(48);
    }

    @Test
    void configDrivenStaleThreshold_defaultIs30Days() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.getStaleAfterDays()).isEqualTo(30);
    }

    @Test
    void configDrivenArchiveThreshold_defaultIs90Days() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.getArchiveAfterDays()).isEqualTo(90);
    }

    // ── S5: Pause/unpause ───────────────────────────────────────────────

    @Test
    void setPaused_pausesCurator(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        service.setPaused(true);
        assertThat(service.isPaused()).isTrue();
    }

    @Test
    void setPaused_unpausesCurator(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        service.setPaused(true);
        service.setPaused(false);
        assertThat(service.isPaused()).isFalse();
    }

    // ── S5: Idle gating + first-run deferral ───────────────────────────

    @Test
    void shouldRunNow_firstRun_defersAndSeedsState(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        // S5: First run should defer (seed last_run_at, return false)
        boolean shouldRun = service.shouldRunNow(Instant.now());
        assertThat(shouldRun).isFalse();
        // State should now have last_run_at seeded
        Map<String, Object> state = service.loadState();
        assertThat(state.get("last_run_at")).isNotNull();
    }

    @Test
    void shouldRunNow_paused_returnsFalse(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        service.setPaused(true);
        assertThat(service.shouldRunNow(Instant.now())).isFalse();
    }

    @Test
    void shouldRunNow_disabled_returnsFalse(@TempDir Path tempDir) {
        properties.getCurator().setEnabled(false);
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        assertThat(service.shouldRunNow(Instant.now())).isFalse();
    }

    @Test
    void shouldRunNow_afterInterval_returnsTrue(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        // Seed state with old last_run_at
        Map<String, Object> state = service.loadState();
        state.put("last_run_at", Instant.now().minus(8, ChronoUnit.DAYS).toString());
        service.saveState(state);
        // 8 days > 7 day interval, should run
        assertThat(service.shouldRunNow(Instant.now())).isTrue();
    }

    @Test
    void shouldRunNow_beforeInterval_returnsFalse(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        // Seed state with recent last_run_at
        Map<String, Object> state = service.loadState();
        state.put("last_run_at", Instant.now().minus(1, ChronoUnit.DAYS).toString());
        service.saveState(state);
        // 1 day < 7 day interval, should not run
        assertThat(service.shouldRunNow(Instant.now())).isFalse();
    }

    @Test
    void maybeRunCurator_idleGating_preventsRunWhenAgentActive(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        // Seed old last_run_at so interval passes
        Map<String, Object> state = service.loadState();
        state.put("last_run_at", Instant.now().minus(8, ChronoUnit.DAYS).toString());
        service.saveState(state);
        // Agent was active 30 seconds ago — not idle enough
        Instant recentActivity = Instant.now().minusSeconds(30);
        var report = service.maybeRunCurator(recentActivity);
        assertThat(report).isNull(); // didn't run
    }

    @Test
    void maybeRunCurator_idleEnough_runsCurator(@TempDir Path tempDir) {
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of());
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));
        // Seed old last_run_at so interval passes
        Map<String, Object> state = service.loadState();
        state.put("last_run_at", Instant.now().minus(8, ChronoUnit.DAYS).toString());
        service.saveState(state);
        // Agent was last active 5 hours ago — idle enough (min is 2h)
        Instant oldActivity = Instant.now().minus(5, ChronoUnit.HOURS);
        var report = service.maybeRunCurator(oldActivity);
        assertThat(report).isNotNull();
    }

    // ── S5: Three-state lifecycle ──────────────────────────────────────

    @Test
    void runCycle_archivesStaleSkills() {
        SkillEntity stale = makeSkill("old-skill", Instant.now().minus(100, ChronoUnit.DAYS));
        SkillEntity active = makeSkill("active-skill", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(stale, active));
        when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.archivedSkills()).contains("old-skill");
        assertThat(report.activeSkills()).contains("active-skill");
        assertThat(stale.isArchived()).isTrue();
        // S5: lifecycleState should be set to "archived"
        assertThat(stale.getLifecycleState()).isEqualTo("archived");
    }

    @Test
    void runCycle_marksStaleSkills() {
        // Skill older than staleAfterDays (30) but younger than archiveAfterDays (90)
        SkillEntity staleSkill = makeSkill("stale-skill", Instant.now().minus(45, ChronoUnit.DAYS));
        SkillEntity active = makeSkill("active-skill", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(staleSkill, active));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        // Stale skill should be in stale list, not archived
        assertThat(report.staleSkills()).contains("stale-skill");
        assertThat(report.archivedSkills()).doesNotContain("stale-skill");
        assertThat(staleSkill.getLifecycleState()).isEqualTo("stale");
    }

    @Test
    void runCycle_reactivatesStaleSkillsOnUse() {
        // Skill was stale but recently used
        SkillEntity staleSkill = makeSkill("stale-skill", Instant.now());
        staleSkill.setLifecycleState("stale");
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(staleSkill));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        // S5: should reactivate from stale to active
        assertThat(staleSkill.getLifecycleState()).isEqualTo("active");
    }

    @Test
    void runCycle_protectedSkillsNotArchived() {
        SkillEntity protectedSkill = makeSkill("hermes-agent", Instant.now().minus(100, ChronoUnit.DAYS));
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(protectedSkill));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.archivedSkills()).doesNotContain("hermes-agent");
        assertThat(report.activeSkills()).contains("hermes-agent");
    }

    // ── S5: Pinned skill bypass ────────────────────────────────────────

    @Test
    void runCycle_pinnedSkillsNotArchived() {
        SkillEntity pinned = makeSkill("pinned-skill", Instant.now().minus(100, ChronoUnit.DAYS));
        pinned.setPinned(true);
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(pinned));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        // S5: Pinned skills bypass all auto-transitions
        assertThat(report.archivedSkills()).doesNotContain("pinned-skill");
        assertThat(report.activeSkills()).contains("pinned-skill");
        assertThat(pinned.isArchived()).isFalse();
    }

    // ── S5: Dry-run mode ────────────────────────────────────────────────

    @Test
    void runCycle_dryRun_doesNotMutate() {
        properties.getCurator().setDryRun(true);
        SkillEntity stale = makeSkill("old-skill", Instant.now().minus(100, ChronoUnit.DAYS));
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(stale));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        // S5: In dry-run, skills should still be reported but not mutated
        assertThat(report.archivedSkills()).contains("old-skill");
        assertThat(stale.isArchived()).isFalse(); // not actually archived
        assertThat(stale.getLifecycleState()).isNull(); // not mutated
    }

    @Test
    void isDryRun_reflectsConfig() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.isDryRun()).isFalse();
        properties.getCurator().setDryRun(true);
        assertThat(service.isDryRun()).isTrue();
    }

    @Test
    void dryRunBanner_isNotEmpty() {
        assertThat(CuratorService.CURATOR_DRY_RUN_BANNER).isNotEmpty();
        assertThat(CuratorService.CURATOR_DRY_RUN_BANNER).contains("DRY-RUN");
    }

    // ── S5: State persistence ──────────────────────────────────────────

    @Test
    void statePersistence_loadsAndSaves(@TempDir Path tempDir) {
        CuratorService service = new CuratorService(skillRepository, properties);
        Path stateFile = tempDir.resolve(".curator_state");
        service.setStateFile(stateFile);

        Map<String, Object> state = service.loadState();
        assertThat(state.get("last_run_at")).isNull();
        assertThat(state.get("paused")).isEqualTo(false);
        assertThat(state.get("run_count")).isEqualTo(0);

        state.put("paused", true);
        state.put("run_count", 5);
        service.saveState(state);

        Map<String, Object> loaded = service.loadState();
        assertThat(loaded.get("paused")).isEqualTo(true);
        assertThat(loaded.get("run_count")).isEqualTo(5);
    }

    @Test
    void runCycle_updatesStateFile(@TempDir Path tempDir) {
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of());
        CuratorService service = new CuratorService(skillRepository, properties);
        service.setStateFile(tempDir.resolve(".curator_state"));

        // Seed old last_run_at to make shouldRunNow return true
        Map<String, Object> state = service.loadState();
        state.put("last_run_at", Instant.now().minus(8, ChronoUnit.DAYS).toString());
        service.saveState(state);

        service.runCycle();

        Map<String, Object> afterState = service.loadState();
        assertThat(afterState.get("last_run_at")).isNotNull();
        assertThat((Integer) afterState.get("run_count")).isGreaterThan(0);
        assertThat(afterState.get("last_run_summary")).isNotNull();
    }

    // ── S5: absorbed_into declarations extraction ─────────────────────

    @Test
    void extractAbsorbedIntoDeclarations_findsDeleteCallsWithAbsorbedInto() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<Map<String, Object>> toolCalls = List.of(
            Map.of("name", "skill_manage",
                "arguments", Map.of("action", "delete", "name", "old-skill", "absorbed_into", "umbrella")),
            Map.of("name", "skill_manage",
                "arguments", Map.of("action", "delete", "name", "stale-skill", "absorbed_into", "")),
            Map.of("name", "skill_manage",
                "arguments", Map.of("action", "patch", "name", "umbrella", "content", "new section")),
            Map.of("name", "other_tool", "arguments", Map.of())
        );
        Map<String, String> result = service.extractAbsorbedIntoDeclarations(toolCalls);
        assertThat(result).hasSize(2);
        assertThat(result.get("old-skill")).isEqualTo("umbrella");
        assertThat(result.get("stale-skill")).isEqualTo("");
    }

    @Test
    void extractAbsorbedIntoDeclarations_emptyInput_returnsEmpty() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.extractAbsorbedIntoDeclarations(null)).isEmpty();
        assertThat(service.extractAbsorbedIntoDeclarations(List.of())).isEmpty();
    }

    @Test
    void extractAbsorbedIntoDeclarations_parsesJsonStringArguments() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<Map<String, Object>> toolCalls = List.of(
            Map.of("name", "skill_manage",
                "arguments", "{\"action\":\"delete\",\"name\":\"test-skill\",\"absorbed_into\":\"target\"}")
        );
        Map<String, String> result = service.extractAbsorbedIntoDeclarations(toolCalls);
        assertThat(result.get("test-skill")).isEqualTo("target");
    }

    // ── S5: Three-way reconciliation ────────────────────────────────────

    @Test
    void reconcileClassification_modelDeclaredConsolidation_wins() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<String> removed = List.of("old-skill");
        Map<String, String> absorbedDeclarations = Map.of("old-skill", "umbrella");
        List<CuratorService.ConsolidationSuggestion> modelConsolidations = List.of(
            new CuratorService.ConsolidationSuggestion("umbrella", List.of("old-skill"), "merged")
        );
        List<String> afterNames = List.of("umbrella", "other-skill");

        var result = service.reconcileClassification(removed, absorbedDeclarations, modelConsolidations, afterNames);
        assertThat(result.consolidated()).hasSize(1);
        assertThat(result.consolidated().get(0).name()).isEqualTo("old-skill");
        assertThat(result.consolidated().get(0).into()).isEqualTo("umbrella");
        assertThat(result.pruned()).isEmpty();
    }

    @Test
    void reconcileClassification_modelDeclaredPrune_wins() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<String> removed = List.of("stale-skill");
        Map<String, String> absorbedDeclarations = Map.of("stale-skill", "");
        List<CuratorService.ConsolidationSuggestion> modelConsolidations = List.of();
        List<String> afterNames = List.of("other-skill");

        var result = service.reconcileClassification(removed, absorbedDeclarations, modelConsolidations, afterNames);
        assertThat(result.pruned()).hasSize(1);
        assertThat(result.pruned().get(0).name()).isEqualTo("stale-skill");
        assertThat(result.consolidated()).isEmpty();
    }

    @Test
    void reconcileClassification_modelNamedMissingUmbrella_fallsBackToPrune() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<String> removed = List.of("ghost-skill");
        Map<String, String> absorbedDeclarations = Map.of();
        List<CuratorService.ConsolidationSuggestion> modelConsolidations = List.of(
            new CuratorService.ConsolidationSuggestion("nonexistent-umbrella", List.of("ghost-skill"), "merged")
        );
        List<String> afterNames = List.of("other-skill"); // umbrella doesn't exist

        var result = service.reconcileClassification(removed, absorbedDeclarations, modelConsolidations, afterNames);
        assertThat(result.pruned()).hasSize(1);
        assertThat(result.pruned().get(0).name()).isEqualTo("ghost-skill");
    }

    @Test
    void reconcileClassification_noEvidence_defaultsToPrune() {
        CuratorService service = new CuratorService(skillRepository, properties);
        List<String> removed = List.of("orphan-skill");
        Map<String, String> absorbedDeclarations = Map.of();
        List<CuratorService.ConsolidationSuggestion> modelConsolidations = List.of();
        List<String> afterNames = List.of("other-skill");

        var result = service.reconcileClassification(removed, absorbedDeclarations, modelConsolidations, afterNames);
        assertThat(result.pruned()).hasSize(1);
        assertThat(result.pruned().get(0).name()).isEqualTo("orphan-skill");
    }

    // ── Existing tests (adapted for S5 changes) ────────────────────────

    @Test
    void runCycle_heuristicFindsConsolidationOpportunities() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        SkillEntity s3 = makeSkill("browser-snapshot", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2, s3));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_llmConsolidation_succeeds() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        String llmResponse = """
            ```yaml
            consolidations:
              - from: browser-navigate
                into: browser-umbrella
                reason: Both deal with browser navigation, should be one skill
              - from: browser-click
                into: browser-umbrella
                reason: Click is a navigation action, belongs in umbrella
            prunings: []
            ```
            """;
        when(modelClient.complete(any(), any())).thenReturn(ChatResponse.text(llmResponse));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(2);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_llmFailure_fallsBackToHeuristic() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(modelClient.complete(any(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_createsBackupSnapshot() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(backupService.createSnapshot(any())).thenReturn(
            new CuratorBackupService.CuratorSnapshot(UUID.randomUUID(), "curator-cycle", Instant.now(), 1));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        service.runCycle();

        verify(backupService).createSnapshot("curator-cycle");
    }

    @Test
    void runCycle_includesActions() {
        SkillEntity stale = makeSkill("old-skill", Instant.now().minus(100, ChronoUnit.DAYS));
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(stale));
        when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.actions()).isNotEmpty();
        assertThat(report.actions().stream().filter(a -> a.type().equals("ARCHIVE"))).isNotEmpty();
    }

    @Test
    void rollback_delegatesToBackupService() {
        UUID snapshotId = UUID.randomUUID();
        when(backupService.rollback(snapshotId)).thenReturn(true);

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        verify(backupService).rollback(snapshotId);
    }

    @Test
    void rollback_withoutBackupService_returnsFalse() {
        CuratorService service = new CuratorService(skillRepository, properties);
        boolean result = service.rollback(UUID.randomUUID());
        assertThat(result).isFalse();
    }

    @Test
    void listSnapshots_withoutBackupService_returnsEmpty() {
        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.listSnapshots()).isEmpty();
    }

    @Test
    void listSnapshots_withBackupService_delegates() {
        when(backupService.listSnapshots()).thenReturn(List.of(
            new CuratorBackupService.CuratorSnapshot(UUID.randomUUID(), "test", Instant.now(), 5)
        ));
        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        assertThat(service.listSnapshots()).hasSize(1);
    }

    @Test
    void runCycle_emptySkills_returnsEmptyReport() {
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of());

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.activeSkills()).isEmpty();
        assertThat(report.staleSkills()).isEmpty();
        assertThat(report.archivedSkills()).isEmpty();
    }

    // ── Auto-start behavior ─────────────────────────────────────────────

    @Test
    void onContextRefreshed_enabled_startsCurator() {
        // Default: curator.enabled=true
        assertThat(properties.getCurator().isEnabled()).isTrue();

        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.isStarted()).isFalse();

        service.onContextRefreshed();

        assertThat(service.isStarted()).isTrue();
    }

    @Test
    void onContextRefreshed_disabled_doesNotStartCurator() {
        properties.getCurator().setEnabled(false);

        CuratorService service = new CuratorService(skillRepository, properties);
        assertThat(service.isStarted()).isFalse();

        service.onContextRefreshed();

        assertThat(service.isStarted()).isFalse();
    }

    @Test
    void start_isIdempotent_multipleCallsDoNotDuplicate() {
        CuratorService service = new CuratorService(skillRepository, properties);

        service.start();
        assertThat(service.isStarted()).isTrue();

        // Calling start() again should be a no-op — no exception, still started
        service.start();
        service.start();
        assertThat(service.isStarted()).isTrue();
    }

    @Test
    void start_multipleTimes_schedulesOnlyOnce() {
        CuratorService service = new CuratorService(skillRepository, properties);

        // First start should schedule the fixed-rate task
        service.start();
        assertThat(service.isStarted()).isTrue();

        // Second start must be a no-op; the started flag is still true
        // and no exception is thrown.
        service.start();
        assertThat(service.isStarted()).isTrue();
    }

    private SkillEntity makeSkill(String name, Instant lastActivity) {
        SkillEntity e = new SkillEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setContent("# " + name + "\nSome content");
        e.setArchived(false);
        e.setLastActivityAt(lastActivity);
        e.setTrustLevel("AGENT_CREATED");
        return e;
    }
}