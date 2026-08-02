package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.persistence.repository.CheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckpointManagerTest {

    @Mock private CheckpointRepository checkpointRepository;
    private AgentProperties properties;
    private CheckpointManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        properties.getCheckpoints().setEnabled(true);
        manager = new CheckpointManager(checkpointRepository, properties, new ObjectMapper());
    }

    @Test
    void snapshotCreatesEntity() {
        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        CheckpointEntity entity = manager.snapshot("test snapshot");
        assertThat(entity.getDescription()).isEqualTo("test snapshot");
        assertThat(entity.getFileCount()).isGreaterThanOrEqualTo(0);
        assertThat(entity.getCreatedAt()).isNotNull();
        verify(checkpointRepository).save(any());
    }

    @Test
    void listReturnsEntities() {
        CheckpointEntity e = new CheckpointEntity();
        e.setId(UUID.randomUUID());
        e.setDescription("test");
        when(checkpointRepository.findAll()).thenReturn(List.of(e));
        List<CheckpointEntity> result = manager.list();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDescription()).isEqualTo("test");
    }

    @Test
    void restoreVerifiesFiles() {
        UUID id = UUID.randomUUID();
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[]");
        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    @Test
    void removeDeletesEntity() {
        UUID id = UUID.randomUUID();
        manager.remove(id);
        verify(checkpointRepository).deleteById(id);
    }

    @Test
    void diffComparesTwoCheckpoints() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();

        CheckpointEntity left = new CheckpointEntity();
        left.setId(leftId);
        left.setFilesJson("[{\"path\":\"a.txt\",\"hash\":\"abc\"},{\"path\":\"b.txt\",\"hash\":\"def\"}]");
        left.setFileCount(2);

        CheckpointEntity right = new CheckpointEntity();
        right.setId(rightId);
        right.setFilesJson("[{\"path\":\"a.txt\",\"hash\":\"xyz\"},{\"path\":\"c.txt\",\"hash\":\"ghi\"}]");
        right.setFileCount(2);

        when(checkpointRepository.findById(leftId)).thenReturn(java.util.Optional.of(left));
        when(checkpointRepository.findById(rightId)).thenReturn(java.util.Optional.of(right));

        var result = manager.diff(leftId, rightId, "context");

        assertThat(result.path("changed")).hasSize(1);
        assertThat(result.path("added")).hasSize(1);
        assertThat(result.path("removed")).hasSize(1);
    }

    @Test
    void isDangerousCommandDetectsRm() {
        assertThat(manager.isDangerousCommand("rm -rf /tmp")).isTrue();
        assertThat(manager.isDangerousCommand("mkfs.ext4 /dev/sda1")).isTrue();
        assertThat(manager.isDangerousCommand("ls -la")).isFalse();
        assertThat(manager.isDangerousCommand("echo hello")).isFalse();
    }
}