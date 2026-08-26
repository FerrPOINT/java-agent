package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.persistence.entity.CheckpointFileEntity;
import com.azhukov.agent.persistence.repository.CheckpointFileRepository;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for CheckpointManager — focuses on:
 * restore with changed files (no stored content), large file skipping,
 * hidden file filtering, diff with identical files, and dangerous command patterns.
 */
@ExtendWith(MockitoExtension.class)
class CheckpointManagerBranchCoverageTest {

    @Mock private CheckpointRepository checkpointRepository;
    @Mock private CheckpointFileRepository checkpointFileRepository;
    private AgentProperties properties;
    private CheckpointManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        properties.getCheckpoints().setEnabled(true);
        manager = new CheckpointManager(checkpointRepository, checkpointFileRepository, properties, new ObjectMapper(),
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public CheckpointManager getObject() { return manager; }
            });
    }

    // ── Hidden file filtering ──

    @Test
    void snapshotFiltersHiddenFiles() throws Exception {
        Files.writeString(tempDir.resolve(".hidden"), "secret");
        Files.writeString(tempDir.resolve("__pycache__"), "cache");
        Files.writeString(tempDir.resolve("visible.txt"), "content");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("test");
        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("visible.txt");
    }

    // ── Diff with identical checkpoints ──

    @Test
    void diffWithIdenticalCheckpointsReturnsNoChanges() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();

        String filesJson = "[{\"path\":\"a.txt\",\"hash\":\"abc\"}]";

        CheckpointEntity left = new CheckpointEntity();
        left.setId(leftId);
        left.setFilesJson(filesJson);
        left.setFileCount(1);

        CheckpointEntity right = new CheckpointEntity();
        right.setId(rightId);
        right.setFilesJson(filesJson);
        right.setFileCount(1);

        when(checkpointRepository.findById(leftId)).thenReturn(Optional.of(left));
        when(checkpointRepository.findById(rightId)).thenReturn(Optional.of(right));

        var result = manager.diff(leftId, rightId, "context");
        assertThat(result.path("changed")).isEmpty();
        assertThat(result.path("added")).isEmpty();
        assertThat(result.path("removed")).isEmpty();
    }

    // ── Diff throws when right not found ──

    @Test
    void diffThrowsWhenRightNotFound() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();

        CheckpointEntity left = new CheckpointEntity();
        left.setId(leftId);
        left.setFilesJson("[]");
        left.setFileCount(0);

        when(checkpointRepository.findById(leftId)).thenReturn(Optional.of(left));
        when(checkpointRepository.findById(rightId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> manager.diff(leftId, rightId, "context"));
    }

    // ── Restore with changed file and stored content ──

    @Test
    void restoreChangedFileWithStoredContentRestoresFile() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("changed.txt");
        String originalContent = "original";
        String modifiedContent = "modified";

        Files.writeString(testFile, originalContent);
        String originalHash = sha256Short(testFile);
        Files.writeString(testFile, modifiedContent);

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"changed.txt\",\"hash\":\"" + originalHash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("changed.txt");
        fileEntity.setFileHash(originalHash);
        fileEntity.setFileSize(originalContent.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(originalContent.getBytes()));

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        manager.restore(id);

        assertThat(Files.readString(testFile)).isEqualTo(originalContent);
    }

    // ── Restore with missing file and no stored content ──

    @Test
    void restoreMissingFileNoStoredContentLogsWarning() {
        UUID id = UUID.randomUUID();
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"missing.txt\",\"hash\":\"abc\"}]");
        entity.setFileCount(1);

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        // Should not throw — just logs
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    // ── Restore with changed file and no stored content ──

    @Test
    void restoreChangedFileNoStoredContentLogsWarning() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("changed.txt");
        Files.writeString(testFile, "current content");

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"changed.txt\",\"hash\":\"0000000000000000\"}]");
        entity.setFileCount(1);

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        manager.restore(id);
        // File should remain unchanged
        assertThat(Files.readString(testFile)).isEqualTo("current content");
    }

    // ── Restore with invalid JSON ──

    @Test
    void restoreWithInvalidFilesJsonThrowsRuntimeException() {
        UUID id = UUID.randomUUID();
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("INVALID JSON");
        entity.setFileCount(0);

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> manager.restore(id));
    }

    // ── Snapshot with empty directory ──

    @Test
    void snapshotEmptyDirectoryCreatesZeroFileCheckpoint() {
        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("empty");
        assertThat(entity.getFileCount()).isZero();
        assertThat(entity.getTotalSizeBytes()).isZero();
    }

    // ── Snapshot with description ──

    @Test
    void snapshotWithNullDescriptionCreatesCheckpoint() {
        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot(null);
        assertThat(entity.getDescription()).isNull();
    }

    // ── Prune with exact count ──

    @Test
    void pruneWithExactCountRemovesNothing() {
        CheckpointEntity e1 = new CheckpointEntity();
        e1.setId(UUID.randomUUID());
        e1.setCreatedAt(java.time.Instant.now());

        when(checkpointRepository.findAll()).thenReturn(List.of(e1));

        int removed = manager.prune(1);
        assertThat(removed).isZero();
    }

    // ── isDangerousCommand edge cases ──

    @Test
    void isDangerousCommandWithEmptyStringReturnsFalse() {
        assertThat(manager.isDangerousCommand("")).isFalse();
    }

    @Test
    void isDangerousCommandWithWhitespaceReturnsFalse() {
        assertThat(manager.isDangerousCommand("   ")).isFalse();
    }

    @Test
    void isDangerousCommandWithRmWithoutFlagsReturnsFalse() {
        assertThat(manager.isDangerousCommand("rm file.txt")).isFalse();
    }

    @Test
    void isDangerousCommandWithMvToEtcReturnsTrue() {
        assertThat(manager.isDangerousCommand("mv file /etc/passwd")).isTrue();
    }

    @Test
    void isDangerousCommandWithMvToUsrReturnsTrue() {
        assertThat(manager.isDangerousCommand("mv file /usr/bin/")).isTrue();
    }

    @Test
    void isDangerousCommandWithMvToBinReturnsTrue() {
        assertThat(manager.isDangerousCommand("mv file /bin/")).isTrue();
    }

    @Test
    void isDangerousCommandWithMvToSafePathReturnsFalse() {
        assertThat(manager.isDangerousCommand("mv file /tmp/")).isFalse();
    }

    @Test
    void isDangerousCommandWithChmod000ReturnsTrue() {
        assertThat(manager.isDangerousCommand("chmod 000 /important")).isTrue();
    }

    @Test
    void isDangerousCommandWithChmod755ReturnsFalse() {
        assertThat(manager.isDangerousCommand("chmod 755 /important")).isFalse();
    }

    @Test
    void isDangerousCommandWithRedirectToDevReturnsTrue() {
        assertThat(manager.isDangerousCommand("echo data > /dev/sda")).isTrue();
    }

    @Test
    void isDangerousCommandWithRedirectToDevNullReturnsTrue() {
        // The pattern "> /dev/" matches "/dev/null" too
        assertThat(manager.isDangerousCommand("echo data > /dev/null")).isTrue();
    }

    @Test
    void isDangerousCommandWithDdIfReturnsTrue() {
        assertThat(manager.isDangerousCommand("dd if=/dev/zero of=/dev/sda")).isTrue();
    }

    @Test
    void isDangerousCommandWithMkfsReturnsTrue() {
        assertThat(manager.isDangerousCommand("mkfs /dev/sda1")).isTrue();
    }

    @Test
    void isDangerousCommandWithChownReturnsTrue() {
        assertThat(manager.isDangerousCommand("chown root:root /file")).isTrue();
    }

    @Test
    void isDangerousCommandWithRmFrReturnsTrue() {
        assertThat(manager.isDangerousCommand("rm -fr /")).isTrue();
    }

    @Test
    void isDangerousCommandWithRmRReturnsTrue() {
        assertThat(manager.isDangerousCommand("rm -r /")).isTrue();
    }

    @Test
    void isDangerousCommandWithSafeGitCommandReturnsFalse() {
        assertThat(manager.isDangerousCommand("git push origin main")).isFalse();
    }

    @Test
    void isDangerousCommandWithSafeLsCommandReturnsFalse() {
        assertThat(manager.isDangerousCommand("ls -la /home")).isFalse();
    }

    // ── List returns sorted by creation date descending ──

    @Test
    void listReturnsSortedByCreationDateDescending() {
        java.time.Instant t1 = java.time.Instant.now().minusSeconds(100);
        java.time.Instant t2 = java.time.Instant.now();

        CheckpointEntity e1 = new CheckpointEntity();
        e1.setId(UUID.randomUUID());
        e1.setCreatedAt(t1);
        e1.setDescription("older");

        CheckpointEntity e2 = new CheckpointEntity();
        e2.setId(UUID.randomUUID());
        e2.setCreatedAt(t2);
        e2.setDescription("newer");

        when(checkpointRepository.findAll()).thenReturn(List.of(e1, e2));

        List<CheckpointEntity> result = manager.list();
        assertThat(result.get(0).getDescription()).isEqualTo("newer");
        assertThat(result.get(1).getDescription()).isEqualTo("older");
    }

    // ── Remove ──

    @Test
    void removeDeletesFromBothRepositories() {
        UUID id = UUID.randomUUID();
        manager.remove(id);
        verify(checkpointFileRepository).deleteByCheckpointId(id);
        verify(checkpointRepository).deleteById(id);
    }

    private static String sha256Short(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 16);
    }
}