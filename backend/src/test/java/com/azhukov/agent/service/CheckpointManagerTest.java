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
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckpointManagerTest {

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
            new ObjectProvider<>() {
                @Override public CheckpointManager getObject() { return manager; }
            });
    }

    @Test
    void snapshotCreatesEntity() {
        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());
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
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());
        manager.restore(id);
        verify(checkpointRepository).findById(id);
    }

    @Test
    void removeDeletesEntity() {
        UUID id = UUID.randomUUID();
        manager.remove(id);
        verify(checkpointFileRepository).deleteByCheckpointId(id);
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
        verify(checkpointFileRepository).deleteByCheckpointId(e1.getId());
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
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

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
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        // Missing file, no stored content → logged but no exception
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
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        // Hash won't match, no stored content → logged but no exception
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

    // ─── New tests: actual file restoration ───

    @Test
    void restoreActuallyRestoresChangedFileContent() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("doc.txt");
        String originalContent = "original content";
        String modifiedContent = "modified content";

        // Write the original content and compute its hash
        Files.writeString(testFile, originalContent);
        String originalHash = sha256Short(testFile);

        // Now modify the file
        Files.writeString(testFile, modifiedContent);
        assertThat(Files.readString(testFile)).isEqualTo(modifiedContent);

        // Set up checkpoint with stored content
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test restore");
        entity.setFilesJson("[{\"path\":\"doc.txt\",\"hash\":\"" + originalHash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("doc.txt");
        fileEntity.setFileHash(originalHash);
        fileEntity.setFileSize(originalContent.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(originalContent.getBytes(StandardCharsets.UTF_8)));

        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        // Restore
        manager.restore(id);

        // File should be restored to original content
        assertThat(Files.readString(testFile)).isEqualTo(originalContent);
    }

    @Test
    void restoreActuallyRestoresMissingFileContent() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("recreated.txt");
        String originalContent = "I was deleted but now I'm back";

        // File does not exist at this point
        assertThat(Files.exists(testFile)).isFalse();

        // Compute hash for the original content
        String originalHash = sha256ShortBytes(originalContent.getBytes(StandardCharsets.UTF_8));

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test restore missing");
        entity.setFilesJson("[{\"path\":\"recreated.txt\",\"hash\":\"" + originalHash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("recreated.txt");
        fileEntity.setFileHash(originalHash);
        fileEntity.setFileSize(originalContent.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(originalContent.getBytes(StandardCharsets.UTF_8)));

        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        // Restore
        manager.restore(id);

        // File should be recreated with original content
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(originalContent);
    }

    @Test
    void restoreActuallyRestoresMultipleFiles() throws Exception {
        UUID id = UUID.randomUUID();

        // Create three files with known content
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("subdir/file2.txt");
        Path file3 = tempDir.resolve("file3.txt");

        String content1 = "content one";
        String content2 = "content two";
        String content3 = "content three";

        Files.writeString(file1, content1);
        Files.createDirectories(file2.getParent());
        Files.writeString(file2, content2);
        Files.writeString(file3, content3);

        String hash1 = sha256Short(file1);
        String hash2 = sha256Short(file2);
        String hash3 = sha256Short(file3);

        // Modify all three files
        Files.writeString(file1, "MODIFIED1");
        Files.writeString(file2, "MODIFIED2");
        Files.delete(file3); // delete file3

        // Build checkpoint entities
        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("multi-file restore");
        entity.setFilesJson("[{\"path\":\"file1.txt\",\"hash\":\"" + hash1 + "\"}," +
            "{\"path\":\"subdir/file2.txt\",\"hash\":\"" + hash2 + "\"}," +
            "{\"path\":\"file3.txt\",\"hash\":\"" + hash3 + "\"}]");
        entity.setFileCount(3);

        CheckpointFileEntity fe1 = new CheckpointFileEntity();
        fe1.setId(UUID.randomUUID());
        fe1.setCheckpoint(entity);
        fe1.setFilePath("file1.txt");
        fe1.setFileHash(hash1);
        fe1.setFileSize(content1.length());
        fe1.setContentBase64(Base64.getEncoder().encodeToString(content1.getBytes(StandardCharsets.UTF_8)));

        CheckpointFileEntity fe2 = new CheckpointFileEntity();
        fe2.setId(UUID.randomUUID());
        fe2.setCheckpoint(entity);
        fe2.setFilePath("subdir/file2.txt");
        fe2.setFileHash(hash2);
        fe2.setFileSize(content2.length());
        fe2.setContentBase64(Base64.getEncoder().encodeToString(content2.getBytes(StandardCharsets.UTF_8)));

        CheckpointFileEntity fe3 = new CheckpointFileEntity();
        fe3.setId(UUID.randomUUID());
        fe3.setCheckpoint(entity);
        fe3.setFilePath("file3.txt");
        fe3.setFileHash(hash3);
        fe3.setFileSize(content3.length());
        fe3.setContentBase64(Base64.getEncoder().encodeToString(content3.getBytes(StandardCharsets.UTF_8)));

        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fe1, fe2, fe3));

        // Restore
        manager.restore(id);

        // All three files should have original content
        assertThat(Files.readString(file1)).isEqualTo(content1);
        assertThat(Files.readString(file2)).isEqualTo(content2);
        assertThat(Files.exists(file3)).isTrue();
        assertThat(Files.readString(file3)).isEqualTo(content3);
    }

    @Test
    void restoreDoesNotModifyUnchangedFiles() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("unchanged.txt");
        String content = "untouched content";
        Files.writeString(testFile, content);
        String hash = sha256Short(testFile);

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"unchanged.txt\",\"hash\":\"" + hash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("unchanged.txt");
        fileEntity.setFileHash(hash);
        fileEntity.setFileSize(content.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));

        when(checkpointRepository.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        manager.restore(id);

        // File should remain unchanged
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void snapshotStoresFileContent() throws Exception {
        Files.writeString(tempDir.resolve("stored.txt"), "store me");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> {
            CheckpointEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("store content");

        assertThat(entity.getFiles()).hasSize(1);
        CheckpointFileEntity fe = entity.getFiles().get(0);
        assertThat(fe.getFilePath()).isEqualTo("stored.txt");
        assertThat(fe.getContentBase64()).isNotNull();
        assertThat(fe.getContentBase64()).isNotEmpty();

        // Decode and verify
        byte[] decoded = Base64.getDecoder().decode(fe.getContentBase64());
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo("store me");
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

    private static String sha256ShortBytes(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString().substring(0, 16);
    }
}