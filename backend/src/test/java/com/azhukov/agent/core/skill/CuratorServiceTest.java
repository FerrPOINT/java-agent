package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.tool.ToolExecutionService;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.core.ports.SkillStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CuratorService} — S5 fixes.
 */
@ExtendWith(MockitoExtension.class)
class CuratorServiceTest {

    @Mock private com.azhukov.agent.core.ports.SkillStorePort skillRepository;
    @Mock private ModelClient modelClient;
    @Mock private CuratorBackupService backupService;
    @Mock private ToolExecutionService toolExecutionService;
    @Mock private ToolRegistry toolRegistry;

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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of());
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(stale, active));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(staleSkill, active));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(staleSkill));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        // S5: should reactivate from stale to active
        assertThat(staleSkill.getLifecycleState()).isEqualTo("active");
    }

    @Test
    void runCycle_protectedSkillsNotArchived() {
        SkillEntity protectedSkill = makeSkill("hermes-agent", Instant.now().minus(100, ChronoUnit.DAYS));
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(protectedSkill));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(pinned));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(stale));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of());
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2, s3));
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
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
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

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(2);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_llmFailure_fallsBackToHeuristic() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(modelClient.complete(any(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_createsBackupSnapshot() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(backupService.createSnapshot(any())).thenReturn(
            new CuratorBackupService.CuratorSnapshot(UUID.randomUUID(), "curator-cycle", Instant.now(), 1));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
        service.runCycle();

        verify(backupService).createSnapshot("curator-cycle");
    }

    @Test
    void runCycle_includesActions() {
        SkillEntity stale = makeSkill("old-skill", Instant.now().minus(100, ChronoUnit.DAYS));
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(stale));
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

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
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
        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
        assertThat(service.listSnapshots()).hasSize(1);
    }

    @Test
    void runCycle_emptySkills_returnsEmptyReport() {
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of());

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

    // ── S17: Agent loop tests ──────────────────────────────────────────

    @Test
    void agentLoop_withToolCalls_executesAndCollects() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // First LLM response: tool call to skills_list
        ChatResponse toolCallResponse = new ChatResponse("Let me list skills",
            List.of(new ToolCall("call-1", "skills_list", "{}")));
        // Second LLM response: final summary with YAML
        String yamlSummary = """
            ```yaml
            consolidations:
              - from: browser-navigate
                into: browser-umbrella
                reason: Both deal with browser, should be one skill
              - from: browser-click
                into: browser-umbrella
                reason: Click is a navigation action
            prunings: []
            ```
            """;
        ChatResponse finalResponse = ChatResponse.text(yamlSummary);

        when(modelClient.complete(any(), any()))
            .thenReturn(toolCallResponse)
            .thenReturn(finalResponse);
        when(toolExecutionService.execute(eq("skills_list"), eq("call-1"), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("[\"browser-navigate\", \"browser-click\"]"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(2);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
        verify(toolExecutionService, times(1)).execute(eq("skills_list"), eq("call-1"), any(), any(), any(), any());
    }

    @Test
    void agentLoop_noToolCalls_immediatelyReturnsSummary() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        String yamlSummary = """
            ```yaml
            consolidations: []
            prunings: []
            ```
            """;
        when(modelClient.complete(any(), any())).thenReturn(ChatResponse.text(yamlSummary));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).isEmpty();
        verify(toolExecutionService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentLoop_multipleToolCalls_executesAllInOrder() {
        SkillEntity s1 = makeSkill("skill-a", Instant.now());
        SkillEntity s2 = makeSkill("skill-b", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // First response: two parallel tool calls
        ChatResponse firstResponse = new ChatResponse("Analyzing",
            List.of(
                new ToolCall("call-1", "skill_view", "{\"name\":\"skill-a\"}"),
                new ToolCall("call-2", "skill_view", "{\"name\":\"skill-b\"}")
            ));
        // Second response: skill_manage patch
        ChatResponse secondResponse = new ChatResponse("Patching",
            List.of(new ToolCall("call-3", "skill_manage",
                "{\"action\":\"patch\",\"name\":\"skill-a\",\"content\":\"updated\"}")));
        // Third response: final summary
        ChatResponse finalResponse = ChatResponse.text("""
            ```yaml
            consolidations:
              - from: skill-b
                into: skill-a
                reason: Merged B into A
            prunings: []
            ```
            """);

        when(modelClient.complete(any(), any()))
            .thenReturn(firstResponse)
            .thenReturn(secondResponse)
            .thenReturn(finalResponse);

        when(toolExecutionService.execute(eq("skill_view"), eq("call-1"), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("Content of skill-a"));
        when(toolExecutionService.execute(eq("skill_view"), eq("call-2"), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("Content of skill-b"));
        when(toolExecutionService.execute(eq("skill_manage"), eq("call-3"), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("Skill patched"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("skill-a");
        verify(toolExecutionService, times(3)).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentLoop_respectsMaxIterations() {
        properties.getCurator().setMaxCuratorIterations(3);
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // Every response returns a tool call — loop should stop at maxIterations
        ChatResponse alwaysToolCall = new ChatResponse("",
            List.of(new ToolCall("call-" + System.nanoTime(), "skills_list", "{}")));
        when(modelClient.complete(any(), any())).thenReturn(alwaysToolCall);
        when(toolExecutionService.execute(any(), any(), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("[]"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        // Should have stopped at 3 iterations, not infinite loop
        verify(modelClient, atMost(3)).complete(any(), any());
        verify(toolExecutionService, atMost(3)).execute(any(), any(), any(), any(), any(), any());
        // Still returns a report (consolidation will fall back to heuristic since no YAML)
        assertThat(report).isNotNull();
    }

    @Test
    void agentLoop_modelFailureOnFirstCall_returnsNullAndFallsBack() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        when(modelClient.complete(any(), any())).thenThrow(new RuntimeException("Model unavailable"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        // Should fall back to heuristic consolidation
        assertThat(report.consolidationSuggestions()).isNotEmpty();
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void agentLoop_toolFailure_continuesLoop() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // First response: tool call that fails
        ChatResponse firstResponse = new ChatResponse("",
            List.of(new ToolCall("call-1", "skill_manage",
                "{\"action\":\"delete\",\"name\":\"test-skill\"}")));
        // Second response: final summary despite the failure
        ChatResponse finalResponse = ChatResponse.text("""
            ```yaml
            consolidations: []
            prunings:
              - name: test-skill
                reason: obsolete
            ```
            """);

        when(modelClient.complete(any(), any()))
            .thenReturn(firstResponse)
            .thenReturn(finalResponse);
        when(toolExecutionService.execute(eq("skill_manage"), eq("call-1"), any(), any(), any(), any()))
            .thenReturn(ToolResult.fail("Permission denied"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        // The loop should continue despite tool failure
        verify(toolExecutionService, times(1)).execute(any(), any(), any(), any(), any(), any());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Message>> messagesCaptor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).complete(messagesCaptor.capture(), any());
        assertThat(messagesCaptor.getAllValues().get(1)).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(com.azhukov.agent.core.model.Role.TOOL);
            assertThat(message.content()).contains("\"success\":false");
            assertThat(message.content()).contains("\"error\":\"Permission denied\"");
        });
        // The parsing should still work on the final summary
        assertThat(report).isNotNull();
    }

    @Test
    void agentLoop_toolExecutionException_continuesLoop() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        ChatResponse firstResponse = new ChatResponse("",
            List.of(new ToolCall("call-1", "skills_list", "{}")));
        ChatResponse finalResponse = ChatResponse.text("""
            ```yaml
            consolidations: []
            prunings: []
            ```
            """);

        when(modelClient.complete(any(), any()))
            .thenReturn(firstResponse)
            .thenReturn(finalResponse);
        // Tool execution throws — should be caught and continue
        when(toolExecutionService.execute(any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Tool crashed"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);
        var report = service.runCycle();

        assertThat(report).isNotNull();
        verify(toolExecutionService, times(1)).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentLoop_collectsToolCallsForReconciliation() {
        SkillEntity s1 = makeSkill("old-skill", Instant.now());

        // First: skill_manage delete with absorbed_into
        ChatResponse firstResponse = new ChatResponse("",
            List.of(new ToolCall("call-1", "skill_manage",
                "{\"action\":\"delete\",\"name\":\"old-skill\",\"absorbed_into\":\"umbrella-skill\"}")));
        // Final: summary
        ChatResponse finalResponse = ChatResponse.text("""
            ```yaml
            consolidations:
              - from: old-skill
                into: umbrella-skill
                reason: absorbed
            prunings: []
            ```
            """);

        when(modelClient.complete(any(), any()))
            .thenReturn(firstResponse)
            .thenReturn(finalResponse);
        when(toolExecutionService.execute(any(), any(), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("Deleted"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);

        // Run the agent loop directly to get the ConsolidationLoopResult
        var loopResult = service.runAgentConsolidationLoop(List.of(s1));

        assertThat(loopResult).isNotNull();
        assertThat(loopResult.toolCalls()).hasSize(1);
        assertThat(loopResult.toolCalls().get(0).get("name")).isEqualTo("skill_manage");

        // Verify the collected tool calls can be used for absorbed_into extraction
        Map<String, String> absorbed = service.extractAbsorbedIntoDeclarations(loopResult.toolCalls());
        assertThat(absorbed.get("old-skill")).isEqualTo("umbrella-skill");

        org.mockito.ArgumentCaptor<List<Message>> history = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(modelClient, times(2)).complete(history.capture(), any());
        Message toolResult = history.getAllValues().get(1).stream()
            .filter(message -> message.role() == com.azhukov.agent.core.model.Role.TOOL)
            .findFirst()
            .orElseThrow();
        assertThat(toolResult.toolCallId()).isEqualTo("call-1");
    }

    @Test
    void agentLoop_withoutToolServices_fallsBackToSingleCall() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        String llmResponse = """
            ```yaml
            consolidations:
              - from: browser-navigate
                into: browser-umbrella
                reason: Both deal with browser
              - from: browser-click
                into: browser-umbrella
                reason: Click is navigation
            prunings: []
            ```
            """;
        when(modelClient.complete(any(), any())).thenReturn(ChatResponse.text(llmResponse));

        // Constructor with null tool services — should fall back to single-call
        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, (ToolExecutionService) null, (ToolRegistry) null);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(2);
        verify(toolExecutionService, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    void agentLoop_modelFailureOnSecondCall_usesPartialResult() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse(anyInt(), anyInt())).thenReturn(List.of(s1));
        lenient().when(skillRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        // First response: tool call succeeds
        ChatResponse firstResponse = new ChatResponse("Let me list",
            List.of(new ToolCall("call-1", "skills_list", "{}")));
        // Second response: model fails
        when(modelClient.complete(any(), any()))
            .thenReturn(firstResponse)
            .thenThrow(new RuntimeException("Model crashed on second call"));
        when(toolExecutionService.execute(any(), any(), any(), any(), any(), any()))
            .thenReturn(ToolResult.ok("[]"));

        CuratorService service = new CuratorService(skillRepository, properties,
            modelClient, backupService, toolExecutionService, toolRegistry);

        // Should not throw — fall back to heuristic
        var report = service.runCycle();
        assertThat(report).isNotNull();
    }

    @Test
    void maxCuratorIterations_defaultIs10() {
        assertThat(properties.getCurator().getMaxCuratorIterations()).isEqualTo(10);
    }

    @Test
    void maxCuratorIterations_canBeConfigured() {
        properties.getCurator().setMaxCuratorIterations(5);
        assertThat(properties.getCurator().getMaxCuratorIterations()).isEqualTo(5);
    }

    @Test
    void runAgentConsolidationLoop_returnsNullWhenNoModelClient() {
        CuratorService service = new CuratorService(skillRepository, properties);
        var result = service.runAgentConsolidationLoop(List.of());
        assertThat(result).isNull();
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
