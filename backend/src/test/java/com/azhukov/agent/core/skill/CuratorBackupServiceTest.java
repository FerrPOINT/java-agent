package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.CuratorSnapshotEntity;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.CuratorSnapshotRepository;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Tests for {@link CuratorBackupService}.
 */
@ExtendWith(MockitoExtension.class)
class CuratorBackupServiceTest {

    @Mock private SkillRepository skillRepository;
    @Mock private CuratorSnapshotRepository snapshotRepository;

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
    void createSnapshot_serializesSkills() {
        SkillEntity s1 = makeSkill("skill-a", false);
        SkillEntity s2 = makeSkill("skill-b", true);
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
    void rollback_restoresSkills() {
        UUID snapshotId = UUID.randomUUID();
        CuratorSnapshotEntity snapshot = makeSnapshotEntity("test", Instant.now());
        snapshot.setId(snapshotId);
        snapshot.setSnapshotData("=== skill-a ===\narchived: false\ntrustLevel: AGENT_CREATED\nupdatedAt: null\nlastActivityAt: null\n---CONTENT---\n# Skill A\n---END---\n=== skill-b ===\narchived: true\ntrustLevel: AGENT_CREATED\nupdatedAt: null\nlastActivityAt: null\n---CONTENT---\n# Skill B\n---END---\n");

        SkillEntity skillA = makeSkill("skill-a", false);
        SkillEntity skillB = makeSkill("skill-b", false);

        when(snapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(skillRepository.findByName("skill-a")).thenReturn(Optional.of(skillA));
        when(skillRepository.findByName("skill-b")).thenReturn(Optional.of(skillB));
        when(skillRepository.findAllBy()).thenReturn(List.of()); // for pre-rollback snapshot

        boolean result = service.rollback(snapshotId);

        assertThat(result).isTrue();
        // skill-b should have been restored to archived=true
        assertThat(skillB.isArchived()).isTrue();
        assertThat(skillA.isArchived()).isFalse();
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