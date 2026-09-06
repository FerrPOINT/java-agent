package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.CuratorSnapshotRepository;
import com.azhukov.agent.core.ports.SkillStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CuratorBackupService} — S8 fixes.
 */
@ExtendWith(MockitoExtension.class)
class CuratorBackupServiceTest {

    @Mock private SkillStorePort skillRepository;
    @Mock private com.azhukov.agent.core.ports.CuratorSnapshotPort snapshotRepository;

    private CuratorBackupService service;

    @BeforeEach
    void setUp() {
        service = new CuratorBackupService(skillRepository, snapshotRepository);
    }

    @Test
    void createSnapshot_emptySkills_returnsNull() {
        when(skillRepository.findAllBy()).thenReturn(List.of());
        var snapshot = service.createSnapshot("test");
        assertThat(snapshot).isNull();
    }

    @Test
    void createSnapshot_serializesSkillsWithAllFields() {
        SkillEntity s1 = makeSkill("skill-a", false);
        SkillEntity s2 = makeSkill("skill-b", true);
        s2.setLifecycleState("archived");
        s2.setPinned(true);
        s2.setAbsorbedInto("umbrella");
        when(skillRepository.findAllBy()).thenReturn(List.of(s1, s2));
        when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(snapshotRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        var snapshot = service.createSnapshot("curator-cycle");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.reason()).isEqualTo("curator-cycle");
        assertThat(snapshot.skillCount()).isEqualTo(2);
        verify(snapshotRepository).save(any());
    }

    @Test
    void createSnapshot_generatesManifest() {
        SkillEntity s1 = makeSkill("skill-a", false);
        SkillEntity s2 = makeSkill("skill-b", false);
        when(skillRepository.findAllBy()).thenReturn(List.of(s1, s2));
        when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(snapshotRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        service.createSnapshot("curator-cycle");

        var captor = org.mockito.ArgumentCaptor.forClass(CuratorSnapshotEntity.class);
        verify(snapshotRepository).save(captor.capture());
        CuratorSnapshotEntity saved = captor.getValue();
        // S8: Manifest should be generated with metadata
        assertThat(saved.getManifest()).isNotNull();
        assertThat(saved.getManifest()).contains("skill-a");
        assertThat(saved.getManifest()).contains("skill-b");
        assertThat(saved.getManifest()).contains("skill_count");
    }

    @Test
    void listSnapshots_returnsAllSortedNewestFirst() {
        CuratorSnapshotEntity e1 = makeSnapshotEntity("a", Instant.now().minusSeconds(60));
        CuratorSnapshotEntity e2 = makeSnapshotEntity("b", Instant.now());
        when(snapshotRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(e2, e1));

        var snapshots = service.listSnapshots();

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).reason()).isEqualTo("b");
        assertThat(snapshots.get(1).reason()).isEqualTo("a");
    }

    @Test
    void rollback_snapshotNotFound_returnsFalse() {
        UUID id = UUID.randomUUID();
        when(snapshotRepository.findById(id)).thenReturn(Optional.empty());

        boolean result = service.rollback(id);
        assertThat(result).isFalse();
    }

    @Test
    void rollback_restoresExistingSkills() {
        UUID snapshotId = UUID.randomUUID();
        CuratorSnapshotEntity snapshot = makeSnapshotEntity("test", Instant.now());
        snapshot.setId(snapshotId);
        snapshot.setSnapshotData(
            "=== skill-a ===\narchived: false\ntrustLevel: AGENT_CREATED\n" +
            "lifecycleState: active\npinned: false\nabsorbedInto: \n" +
            "updatedAt: null\nlastActivityAt: null\ncreatedAt: null\n" +
            "category: \nwriteOrigin: \n---CONTENT---\n# Skill A\n---END---\n" +
            "=== skill-b ===\narchived: true\ntrustLevel: AGENT_CREATED\n" +
            "lifecycleState: archived\npinned: false\nabsorbedInto: \n" +
            "updatedAt: null\nlastActivityAt: null\ncreatedAt: null\n" +
            "category: \nwriteOrigin: \n---CONTENT---\n# Skill B\n---END---\n");

        SkillEntity skillA = makeSkill("skill-a", false);
        SkillEntity skillB = makeSkill("skill-b", false);

        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(skillRepository.findByName("skill-a")).thenReturn(Optional.of(skillA));
        when(skillRepository.findByName("skill-b")).thenReturn(Optional.of(skillB));
        when(skillRepository.findAllBy()).thenReturn(List.of()); // for pre-rollback snapshot
        lenient().when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        // skill-b should have been restored to archived=true
        assertThat(skillB.isArchived()).isTrue();
        assertThat(skillB.getLifecycleState()).isEqualTo("archived");
        assertThat(skillA.isArchived()).isFalse();
        assertThat(skillA.getLifecycleState()).isEqualTo("active");
    }

    // S8: Restore deleted skills
    @Test
    void rollback_restoresDeletedSkills() {
        UUID snapshotId = UUID.randomUUID();
        CuratorSnapshotEntity snapshot = makeSnapshotEntity("test", Instant.now());
        snapshot.setId(snapshotId);
        snapshot.setSnapshotData(
            "=== deleted-skill ===\narchived: false\ntrustLevel: AGENT_CREATED\n" +
            "lifecycleState: active\npinned: false\nabsorbedInto: \n" +
            "updatedAt: null\nlastActivityAt: null\ncreatedAt: null\n" +
            "category: \nwriteOrigin: \n---CONTENT---\n# Deleted Skill\n---END---\n");

        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(skillRepository.findByName("deleted-skill")).thenReturn(Optional.empty());
        when(skillRepository.findAllBy()).thenReturn(List.of()); // for pre-rollback snapshot
        when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        // S8: Deleted skill should be restored (created)
        var captor = org.mockito.ArgumentCaptor.forClass(SkillEntity.class);
        verify(skillRepository).save(captor.capture());
        SkillEntity restored = captor.getValue();
        assertThat(restored.getName()).isEqualTo("deleted-skill");
        assertThat(restored.getContent()).isEqualTo("# Deleted Skill");
    }

    @Test
    void rollback_takesPreRollbackSnapshot() {
        UUID snapshotId = UUID.randomUUID();
        CuratorSnapshotEntity snapshot = makeSnapshotEntity("test", Instant.now());
        snapshot.setId(snapshotId);
        snapshot.setSnapshotData(
            "=== skill-a ===\narchived: false\ntrustLevel: AGENT_CREATED\n" +
            "lifecycleState: active\npinned: false\nabsorbedInto: \n" +
            "updatedAt: null\nlastActivityAt: null\ncreatedAt: null\n" +
            "category: \nwriteOrigin: \n---CONTENT---\n# Skill A\n---END---\n");

        SkillEntity skillA = makeSkill("skill-a", false);

        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(skillRepository.findByName("skill-a")).thenReturn(Optional.of(skillA));
        // S8: First call is for pre-rollback snapshot, returns skills list
        when(skillRepository.findAllBy()).thenReturn(List.of(skillA));
        when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // S8: Mock pruneOldSnapshots dependency
        when(snapshotRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        // S8: snapshotRepository.save called once for pre-rollback snapshot
        verify(snapshotRepository, atLeast(1)).save(any());
        // S8: skillRepository.save called to restore the skill
        verify(skillRepository).save(any());
    }

    @Test
    void rollback_preservesPinnedAndAbsorbedInto() {
        UUID snapshotId = UUID.randomUUID();
        CuratorSnapshotEntity snapshot = makeSnapshotEntity("test", Instant.now());
        snapshot.setId(snapshotId);
        snapshot.setSnapshotData(
            "=== skill-a ===\narchived: false\ntrustLevel: AGENT_CREATED\n" +
            "lifecycleState: active\npinned: true\nabsorbedInto: umbrella-skill\n" +
            "updatedAt: null\nlastActivityAt: null\ncreatedAt: null\n" +
            "category: test\nwriteOrigin: AGENT\n---CONTENT---\n# Skill A\n---END---\n");

        SkillEntity skillA = makeSkill("skill-a", false);

        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(skillRepository.findByName("skill-a")).thenReturn(Optional.of(skillA));
        when(skillRepository.findAllBy()).thenReturn(List.of()); // for pre-rollback
        when(skillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(snapshotRepository.save(any())).thenAnswer(inv -> {
            CuratorSnapshotEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        assertThat(skillA.isPinned()).isTrue();
        assertThat(skillA.getAbsorbedInto()).isEqualTo("umbrella-skill");
        assertThat(skillA.getCategory()).isEqualTo("test");
    }

    @Test
    void getSnapshot_returnsSnapshot() {
        UUID id = UUID.randomUUID();
        CuratorSnapshotEntity entity = makeSnapshotEntity("test", Instant.now());
        entity.setId(id);
        when(snapshotRepository.findById(id)).thenReturn(Optional.of(entity));

        var snapshot = service.getSnapshot(id);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.reason()).isEqualTo("test");
    }

    @Test
    void getSnapshot_notFound_returnsNull() {
        UUID id = UUID.randomUUID();
        when(snapshotRepository.findById(id)).thenReturn(Optional.empty());
        var snapshot = service.getSnapshot(id);
        assertThat(snapshot).isNull();
    }

    @Test
    void getSnapshotManifest_returnsManifest() {
        UUID id = UUID.randomUUID();
        CuratorSnapshotEntity entity = makeSnapshotEntity("test", Instant.now());
        entity.setId(id);
        entity.setManifest("{\"skill_count\":5}");
        when(snapshotRepository.findById(id)).thenReturn(Optional.of(entity));

        String manifest = service.getSnapshotManifest(id);
        assertThat(manifest).isNotNull();
        assertThat(manifest).contains("skill_count");
    }

    @Test
    void getSnapshotManifest_notFound_returnsNull() {
        UUID id = UUID.randomUUID();
        when(snapshotRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.getSnapshotManifest(id)).isNull();
    }

    @Test
    void deleteSnapshot_delegates() {
        UUID id = UUID.randomUUID();
        service.deleteSnapshot(id);
        verify(snapshotRepository).deleteById(id);
    }

    private SkillEntity makeSkill(String name, boolean archived) {
        SkillEntity e = new SkillEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setContent("# " + name);
        e.setArchived(archived);
        e.setTrustLevel("AGENT_CREATED");
        return e;
    }

    private CuratorSnapshotEntity makeSnapshotEntity(String reason, Instant createdAt) {
        CuratorSnapshotEntity e = new CuratorSnapshotEntity();
        e.setId(UUID.randomUUID());
        e.setReason(reason);
        e.setCreatedAt(createdAt);
        e.setSkillCount(2);
        e.setSnapshotData("data");
        return e;
    }
}