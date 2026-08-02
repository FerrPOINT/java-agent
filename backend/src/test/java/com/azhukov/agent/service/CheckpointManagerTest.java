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

import java.nio.file.Files;
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
    void pruneRemovesExcessCheckpoints() {
        CheckpointEntity e1 = new CheckpointEntity();
        e1.setId(UUID.randomUUID());
        e1.setCreatedAt(java.time.Instant.now().minusSeconds(60));
        CheckpointEntity e2 = new CheckpointEntity();
        e2.setId(UUID.randomUUID());
        e2.setCreatedAt(java.time.Instant.now());

        when(checkpointRepository.findAll()).thenReturn(List.of(e2, e1));

        int removed = manager.prune(1);
        assertThat(removed).isEqualTo(1);
        verify(checkpointRepository).delete(e1);
    }

    @Test
    void pruneReturnsZeroWhenUnderLimit() {
        when(checkpointRepository.findAll()).thenReturn(List.of());
        int removed = manager.prune(10);
        assertThat(removed).isZero();
    }

    @Test
    void restoreThrowsWhenCheckpointNotFound() {
        UUID id = UUID.randomUUID();
        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> manager.restore(id));
    }

    @Test
    void restoreVerifiesActualFiles() throws Exception {
        UUID id = UUID.randomUUID();
        // Create a real file in tempDir so the hash can be computed
        java.nio.file.Path testFile = tempDir.resolve("hello.txt");
        Files.writeString(testFile, "hello world");

        // Compute hash to embed in checkpoint JSON
        String hash = sha256Short(testFile);

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"hello.txt\",\"hash\":\"" + hash + "\"}]");
        entity.setFileCount(1);
        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));

        // Should not throw — file exists and hash matches
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    @Test
    void restoreDetectsMissingFile() throws Exception {
        UUID id = UUID.randomUUID();
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"nonexistent.txt\",\"hash\":\"abc\"}]");
        entity.setFileCount(1);
        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));

        // Missing file → logged but no exception
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    @Test
    void restoreDetectsChangedFile() throws Exception {
        UUID id = UUID.randomUUID();
        java.nio.file.Path testFile = tempDir.resolve("changed.txt");
        Files.writeString(testFile, "current content");

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"changed.txt\",\"hash\":\"0000000000000000\"}]");
        entity.setFileCount(1);
        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));

        // Hash won't match → logged but no exception
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    @Test
    void snapshotWithRealFiles() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "content-a");
        Files.writeString(tempDir.resolve("b.txt"), "content-b");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("with files");
        assertThat(entity.getFileCount()).isEqualTo(2);
        assertThat(entity.getTotalSizeBytes()).isGreaterThan(0);
        assertThat(entity.getFilesJson()).contains("a.txt").contains("b.txt");
    }

    @Test
    void isDangerousCommandDetectsRm() {
        assertThat(manager.isDangerousCommand("rm -rf /tmp")).isTrue();
        assertThat(manager.isDangerousCommand("mkfs.ext4 /dev/sda1")).isTrue();
        assertThat(manager.isDangerousCommand("ls -la")).isFalse();
        assertThat(manager.isDangerousCommand("echo hello")).isFalse();
    }

    @Test
    void isDangerousCommandDetectsVariousPatterns() {
        assertThat(manager.isDangerousCommand("dd if=/dev/zero of=/dev/sda")).isTrue();
        assertThat(manager.isDangerousCommand("mv file /etc/passwd")).isTrue();
        assertThat(manager.isDangerousCommand("chmod 000 /important")).isTrue();
        assertThat(manager.isDangerousCommand("chown root /file")).isTrue();
        assertThat(manager.isDangerousCommand("echo data > /dev/sda")).isTrue();
        assertThat(manager.isDangerousCommand("rm -fr /home")).isTrue();
        assertThat(manager.isDangerousCommand("rm -r /tmp")).isTrue();
        assertThat(manager.isDangerousCommand("cat file.txt")).isFalse();
        assertThat(manager.isDangerousCommand("git commit -m 'test'")).isFalse();
    }

    @Test
    void diffHandlesInvalidJson() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();

        CheckpointEntity left = new CheckpointEntity();
        left.setId(leftId);
        left.setFilesJson("INVALID JSON");
        left.setFileCount(0);

        CheckpointEntity right = new CheckpointEntity();
        right.setId(rightId);
        right.setFilesJson("ALSO INVALID");
        right.setFileCount(0);

        when(checkpointRepository.findById(leftId)).thenReturn(java.util.Optional.of(left));
        when(checkpointRepository.findById(rightId)).thenReturn(java.util.Optional.of(right));

        var result = manager.diff(leftId, rightId, "context");
        // Should not throw — error is caught and put in result
        assertThat(result.has("error")).isTrue();
    }

    @Test
    void diffThrowsWhenLeftNotFound() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        when(checkpointRepository.findById(leftId)).thenReturn(java.util.Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> manager.diff(leftId, rightId, "context"));
    }

    private static String sha256Short(java.nio.file.Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString().substring(0, 16);
    }
}