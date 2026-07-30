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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CuratorService} — LLM-driven consolidation, snapshot, and lifecycle.
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

    @Test
    void runCycle_archivesStaleSkills() {
        SkillEntity stale = makeSkill("old-skill", Instant.now().minusSeconds(30 * 24 * 60 * 60L));
        SkillEntity active = makeSkill("active-skill", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(stale, active));
        when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.archivedSkills()).contains("old-skill");
        assertThat(report.activeSkills()).contains("active-skill");
        assertThat(stale.isArchived()).isTrue();
    }

    @Test
    void runCycle_protectedSkillsNotArchived() {
        SkillEntity protectedSkill = makeSkill("hermes-agent", Instant.now().minusSeconds(30 * 24 * 60 * 60L));
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(protectedSkill));
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.archivedSkills()).doesNotContain("hermes-agent");
        assertThat(report.activeSkills()).contains("hermes-agent");
    }

    @Test
    void runCycle_heuristicFindsConsolidationOpportunities() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        SkillEntity s3 = makeSkill("browser-snapshot", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2, s3));
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CuratorService service = new CuratorService(skillRepository, properties);
        var report = service.runCycle();

        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
        assertThat(report.consolidationSuggestions().get(0).skillsToMerge())
            .containsExactlyInAnyOrder("browser-navigate", "browser-click", "browser-snapshot");
    }

    @Test
    void runCycle_llmConsolidation_succeeds() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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
        assertThat(report.consolidationSuggestions().get(0).skillsToMerge()).contains("browser-navigate");
        assertThat(report.consolidationSuggestions().get(1).skillsToMerge()).contains("browser-click");
    }

    @Test
    void runCycle_llmFailure_fallsBackToHeuristic() {
        SkillEntity s1 = makeSkill("browser-navigate", Instant.now());
        SkillEntity s2 = makeSkill("browser-click", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1, s2));
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(modelClient.complete(any(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        var report = service.runCycle();

        // Should fall back to heuristic
        assertThat(report.consolidationSuggestions()).hasSize(1);
        assertThat(report.consolidationSuggestions().get(0).suggestedUmbrellaName()).isEqualTo("browser-umbrella");
    }

    @Test
    void runCycle_createsBackupSnapshot() {
        SkillEntity s1 = makeSkill("test-skill", Instant.now());
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(s1));
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(backupService.createSnapshot(any())).thenReturn(
            new CuratorBackupService.CuratorSnapshot(UUID.randomUUID(), "curator-cycle", Instant.now(), 1));

        CuratorService service = new CuratorService(skillRepository, properties, modelClient, backupService);
        service.runCycle();

        verify(backupService).createSnapshot("curator-cycle");
    }

    @Test
    void runCycle_includesActions() {
        SkillEntity stale = makeSkill("old-skill", Instant.now().minusSeconds(30 * 24 * 60 * 60L));
        when(skillRepository.findByArchivedFalse()).thenReturn(List.of(stale));
        when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

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