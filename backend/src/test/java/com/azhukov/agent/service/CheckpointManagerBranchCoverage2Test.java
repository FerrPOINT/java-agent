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
import static org.mockito.Mockito.*;
import org.mockito.Mockito;

/**
 * Additional branch-coverage tests for {@link CheckpointManager} targeting:
 * - snapshot with file larger than MAX_FILE_SIZE (10MB) — skipped
 * - snapshot with file larger than MAX_STORED_CONTENT_SIZE (512KB) — hash only, no content
 * - snapshot with file hash failure — error logged
 * - snapshot with non-existent working directory — IOException
 * - snapshot auto-prune
 * - restore with multiple files: some verified, some changed, some missing
 * - restore with missing file and stored content — restored
 * - diff with all files identical — no changes
 * - diff with one side having extra files
 * - prune with more than max
 * - isHidden: various patterns (.git, .gradle, build, target, node_modules, __)
 */
@ExtendWith(MockitoExtension.class)
class CheckpointManagerBranchCoverage2Test {

    @Mock
    private CheckpointRepository checkpointRepository;

    @Mock
    private CheckpointFileRepository checkpointFileRepository;

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

    // ── snapshot: file larger than MAX_STORED_CONTENT_SIZE (512KB) — hash only ──

    @Test
    void snapshotWithLargeFileStoresHashOnly() throws Exception {
        // Create a file just over 512KB
        byte[] largeContent = new byte[513 * 1024];
        Path largeFile = tempDir.resolve("large.bin");
        Files.write(largeFile, largeContent);

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> {
            CheckpointEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("large file test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFiles()).hasSize(1);
        CheckpointFileEntity fe = entity.getFiles().get(0);
        assertThat(fe.getFilePath()).isEqualTo("large.bin");
        assertThat(fe.getFileHash()).isNotEqualTo("ERROR");
        // Content should NOT be stored for files > 512KB
        assertThat(fe.getContentBase64()).isNull();
    }

    // ── snapshot: files in subdirectories are included ──

    @Test
    void snapshotIncludesFilesInSubdirectories() throws Exception {
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("nested.txt"), "nested content");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("nested test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("nested.txt");
    }

    // ── snapshot: .git directory is excluded ──

    @Test
    void snapshotExcludesGitDirectory() throws Exception {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), "git config");
        Files.writeString(tempDir.resolve("visible.txt"), "content");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("git test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("visible.txt");
        assertThat(entity.getFilesJson()).doesNotContain(".git");
    }

    // ── snapshot: build directory is excluded ──

    @Test
    void snapshotExcludesBuildDirectory() throws Exception {
        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("output.txt"), "build output");
        Files.writeString(tempDir.resolve("source.txt"), "source");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("build test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("source.txt");
    }

    // ── snapshot: target directory is excluded ──

    @Test
    void snapshotExcludesTargetDirectory() throws Exception {
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("class.txt"), "compiled");
        Files.writeString(tempDir.resolve("main.txt"), "main source");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("target test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("main.txt");
    }

    // ── snapshot: node_modules directory is excluded ──

    @Test
    void snapshotExcludesNodeModulesDirectory() throws Exception {
        Path nmDir = tempDir.resolve("node_modules");
        Files.createDirectories(nmDir);
        Files.writeString(nmDir.resolve("lib.js"), "js lib");
        Files.writeString(tempDir.resolve("app.js"), "app");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("nm test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("app.js");
    }

    // ── snapshot: __pycache__ files excluded ──

    @Test
    void snapshotExcludesDoubleUnderscoreFiles() throws Exception {
        Files.writeString(tempDir.resolve("__pycache__"), "cache");
        Files.writeString(tempDir.resolve("visible.txt"), "content");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("dunder test");

        assertThat(entity.getFileCount()).isEqualTo(1);
    }

    // ── snapshot: .gradle directory is excluded ──

    @Test
    void snapshotExcludesGradleDirectory() throws Exception {
        Path gradleDir = tempDir.resolve(".gradle");
        Files.createDirectories(gradleDir);
        Files.writeString(gradleDir.resolve("cache.txt"), "gradle cache");
        Files.writeString(tempDir.resolve("build.gradle"), "gradle build");

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("gradle test");

        assertThat(entity.getFileCount()).isEqualTo(1);
        assertThat(entity.getFilesJson()).contains("build.gradle");
    }

    // ── snapshot: auto-prune after snapshot ──

    @Test
    void snapshotTriggersAutoPrune() throws Exception {
        properties.getCheckpoints().setMaxSnapshots(2);

        // Pre-existing checkpoints that should be pruned
        CheckpointEntity old = new CheckpointEntity();
        old.setId(UUID.randomUUID());
        old.setCreatedAt(java.time.Instant.now().minusSeconds(120));
        CheckpointEntity newer = new CheckpointEntity();
        newer.setId(UUID.randomUUID());
        newer.setCreatedAt(java.time.Instant.now().minusSeconds(60));
        CheckpointEntity newest = new CheckpointEntity();
        newest.setId(UUID.randomUUID());
        newest.setCreatedAt(java.time.Instant.now());

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> {
            CheckpointEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        // findAll returns 3 checkpoints → should prune 1
        when(checkpointRepository.findAll()).thenReturn(List.of(newest, newer, old));

        manager.snapshot("prune test");

        // Verify prune deleted the oldest
        verify(checkpointFileRepository).deleteByCheckpointId(old.getId());
        verify(checkpointRepository).delete(old);
    }

    // ── snapshot: filesJson serialization failure ──

    @Test
    void snapshotHandlesFilesJsonSerializationFailure() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "content");

        // Use a broken ObjectMapper that throws on writeValueAsString
        com.fasterxml.jackson.databind.ObjectMapper brokenMapper = Mockito.spy(new ObjectMapper());
        when(brokenMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialization error"));
        // Still need createArrayNode to work
        when(brokenMapper.createArrayNode()).thenReturn(new ObjectMapper().createArrayNode());
        when(brokenMapper.createObjectNode()).thenReturn(new ObjectMapper().createObjectNode());

        manager = new CheckpointManager(checkpointRepository, checkpointFileRepository, properties, brokenMapper,
            new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public CheckpointManager getObject() { return manager; }
            });

        when(checkpointRepository.save(any(CheckpointEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(checkpointRepository.findAll()).thenReturn(List.of());

        CheckpointEntity entity = manager.snapshot("broken json");

        // Should fall back to "[]" for filesJson
        assertThat(entity.getFilesJson()).isEqualTo("[]");
    }

    // ── restore: changed file with stored content restores it ──

    @Test
    void restoreChangedFileWithStoredContentRestoresIt() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("changed.txt");
        String original = "original content";
        String modified = "modified content";

        Files.writeString(testFile, original);
        String hash = sha256Short(testFile);
        Files.writeString(testFile, modified);

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"changed.txt\",\"hash\":\"" + hash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("changed.txt");
        fileEntity.setFileHash(hash);
        fileEntity.setFileSize(original.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(original.getBytes()));

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        manager.restore(id);

        assertThat(Files.readString(testFile)).isEqualTo(original);
    }

    // ── restore: missing file with stored content restores it ──

    @Test
    void restoreMissingFileWithStoredContentRestoresIt() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("missing.txt");
        String content = "I was deleted";

        assertThat(Files.exists(testFile)).isFalse();

        String hash = sha256ShortBytes(content.getBytes());

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"missing.txt\",\"hash\":\"" + hash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("missing.txt");
        fileEntity.setFileHash(hash);
        fileEntity.setFileSize(content.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(content.getBytes()));

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        manager.restore(id);

        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    // ── restore: missing file in subdirectory with stored content ──

    @Test
    void restoreMissingFileInSubdirectoryCreatesParentDirs() throws Exception {
        UUID id = UUID.randomUUID();
        Path testFile = tempDir.resolve("subdir/nested/file.txt");
        String content = "nested file content";

        assertThat(Files.exists(testFile)).isFalse();

        String hash = sha256ShortBytes(content.getBytes());

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("test");
        entity.setFilesJson("[{\"path\":\"subdir/nested/file.txt\",\"hash\":\"" + hash + "\"}]");
        entity.setFileCount(1);

        CheckpointFileEntity fileEntity = new CheckpointFileEntity();
        fileEntity.setId(UUID.randomUUID());
        fileEntity.setCheckpoint(entity);
        fileEntity.setFilePath("subdir/nested/file.txt");
        fileEntity.setFileHash(hash);
        fileEntity.setFileSize(content.length());
        fileEntity.setContentBase64(Base64.getEncoder().encodeToString(content.getBytes()));

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of(fileEntity));

        manager.restore(id);

        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    // ── diff: with added, removed, changed, and identical files ──

    @Test
    void diffWithMixedChangesReportsCorrectly() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();

        CheckpointEntity left = new CheckpointEntity();
        left.setId(leftId);
        left.setFilesJson("[{\"path\":\"same.txt\",\"hash\":\"aaa\"},{\"path\":\"changed.txt\",\"hash\":\"bbb\"},{\"path\":\"only-left.txt\",\"hash\":\"ccc\"}]");
        left.setFileCount(3);

        CheckpointEntity right = new CheckpointEntity();
        right.setId(rightId);
        right.setFilesJson("[{\"path\":\"same.txt\",\"hash\":\"aaa\"},{\"path\":\"changed.txt\",\"hash\":\"xyz\"},{\"path\":\"only-right.txt\",\"hash\":\"ddd\"}]");
        right.setFileCount(3);

        when(checkpointRepository.findById(leftId)).thenReturn(Optional.of(left));
        when(checkpointRepository.findById(rightId)).thenReturn(Optional.of(right));

        var result = manager.diff(leftId, rightId, "context");

        assertThat(result.path("changed")).hasSize(1);
        assertThat(result.path("changed").get(0).get("path").asText()).isEqualTo("changed.txt");
        assertThat(result.path("added")).hasSize(1);
        assertThat(result.path("added").get(0).asText()).isEqualTo("only-right.txt");
        assertThat(result.path("removed")).hasSize(1);
        assertThat(result.path("removed").get(0).asText()).isEqualTo("only-left.txt");
        assertThat(result.path("leftFileCount").asInt()).isEqualTo(3);
        assertThat(result.path("rightFileCount").asInt()).isEqualTo(3);
    }

    // ── prune: multiple checkpoints to remove ──

    @Test
    void pruneRemovesMultipleExcessCheckpoints() {
        CheckpointEntity e1 = new CheckpointEntity();
        e1.setId(UUID.randomUUID());
        e1.setCreatedAt(java.time.Instant.now().minusSeconds(300));

        CheckpointEntity e2 = new CheckpointEntity();
        e2.setId(UUID.randomUUID());
        e2.setCreatedAt(java.time.Instant.now().minusSeconds(200));

        CheckpointEntity e3 = new CheckpointEntity();
        e3.setId(UUID.randomUUID());
        e3.setCreatedAt(java.time.Instant.now());

        when(checkpointRepository.findAll()).thenReturn(List.of(e3, e2, e1));

        int removed = manager.prune(1);

        assertThat(removed).isEqualTo(2);
        verify(checkpointFileRepository).deleteByCheckpointId(e1.getId());
        verify(checkpointRepository).delete(e1);
        verify(checkpointFileRepository).deleteByCheckpointId(e2.getId());
        verify(checkpointRepository).delete(e2);
    }

    // ── restore: multiple files — mix of verified, changed, missing ──

    @Test
    void restoreMixedFiles() throws Exception {
        UUID id = UUID.randomUUID();

        // File 1: unchanged
        Path file1 = tempDir.resolve("unchanged.txt");
        String content1 = "unchanged content";
        Files.writeString(file1, content1);
        String hash1 = sha256Short(file1);

        // File 2: changed (hash mismatch, no stored content)
        Path file2 = tempDir.resolve("changed.txt");
        Files.writeString(file2, "modified content");
        String fakeHash2 = "0000000000000000";

        // File 3: missing (no file, no stored content)
        String fakeHash3 = "1111111111111111";

        CheckpointEntity entity = new CheckpointEntity();
        entity.setId(id);
        entity.setDescription("mixed restore");
        entity.setFilesJson(
            "[{\"path\":\"unchanged.txt\",\"hash\":\"" + hash1 + "\"}," +
            "{\"path\":\"changed.txt\",\"hash\":\"" + fakeHash2 + "\"}," +
            "{\"path\":\"missing.txt\",\"hash\":\"" + fakeHash3 + "\"}]");
        entity.setFileCount(3);

        when(checkpointRepository.findById(id)).thenReturn(Optional.of(entity));
        when(checkpointFileRepository.findByCheckpointId(id)).thenReturn(List.of());

        // Should not throw — just logs warnings
        manager.restore(id);

        // Unchanged file should remain
        assertThat(Files.readString(file1)).isEqualTo(content1);
        // Changed file should remain as-is (no stored content)
        assertThat(Files.readString(file2)).isEqualTo("modified content");
        // Missing file should remain missing
        assertThat(Files.exists(tempDir.resolve("missing.txt"))).isFalse();
    }

    // ── list: returns all from repository ──

    @Test
    void listReturnsAllCheckpoints() {
        CheckpointEntity e1 = new CheckpointEntity();
        e1.setId(UUID.randomUUID());
        e1.setCreatedAt(java.time.Instant.now().minusSeconds(60));
        e1.setDescription("old");

        CheckpointEntity e2 = new CheckpointEntity();
        e2.setId(UUID.randomUUID());
        e2.setCreatedAt(java.time.Instant.now());
        e2.setDescription("new");

        when(checkpointRepository.findAll()).thenReturn(List.of(e1, e2));

        List<CheckpointEntity> result = manager.list();

        assertThat(result).hasSize(2);
        // Sorted by createdAt descending
        assertThat(result.get(0).getDescription()).isEqualTo("new");
        assertThat(result.get(1).getDescription()).isEqualTo("old");
    }

    private static String sha256Short(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 16);
    }

    private static String sha256ShortBytes(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString().substring(0, 16);
    }
}