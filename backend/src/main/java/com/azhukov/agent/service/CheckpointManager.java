package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.persistence.repository.CheckpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem checkpoint manager — takes lightweight snapshots (file list + hashes, not file contents).
 * Supports snapshot, list, restore, and prune operations.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CheckpointManager {

    private final CheckpointRepository checkpointRepository;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    private static final int MAX_FILES = 10000;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB per file

    public CheckpointEntity snapshot(String description) {
        String workingDir = properties.getCore().getWorkingDirectory();
        Path root = Paths.get(workingDir);
        log.info("Taking checkpoint snapshot of {} — {}", workingDir, description);

        ArrayNode filesArray = objectMapper.createArrayNode();
        int fileCount = 0;
        long totalSize = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> fileList = paths
                .filter(Files::isRegularFile)
                .filter(p -> !isHidden(p))
                .limit(MAX_FILES)
                .toList();

            for (Path p : fileList) {
                try {
                    long size = Files.size(p);
                    if (size > MAX_FILE_SIZE) {
                        log.debug("Skipping large file: {} ({} bytes)", p, size);
                        continue;
                    }
                    String hash = hashFile(p);
                    String relativePath = root.relativize(p).toString();

                    ObjectNode fileNode = objectMapper.createObjectNode();
                    fileNode.put("path", relativePath);
                    fileNode.put("hash", hash);
                    fileNode.put("size", size);
                    filesArray.add(fileNode);

                    fileCount++;
                    totalSize += size;
                } catch (Exception e) {
                    log.debug("Failed to hash file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk directory {}: {}", workingDir, e.getMessage());
        }

        CheckpointEntity entity = new CheckpointEntity();
        entity.setDescription(description);
        entity.setFileCount(fileCount);
        entity.setTotalSizeBytes(totalSize);
        entity.setCreatedAt(Instant.now());
        try {
            entity.setFilesJson(objectMapper.writeValueAsString(filesArray));
        } catch (Exception e) {
            entity.setFilesJson("[]");
        }

        entity = checkpointRepository.save(entity);
        log.info("Checkpoint created: {} files, {} bytes total — {}", fileCount, totalSize, entity.getId());

        // Auto-prune
        prune(properties.getCheckpoints().getMaxSnapshots());

        return entity;
    }

    public List<CheckpointEntity> list() {
        return checkpointRepository.findAll().stream()
            .sorted(Comparator.comparing(CheckpointEntity::getCreatedAt).reversed())
            .toList();
    }

    public void restore(UUID id) {
        CheckpointEntity checkpoint = checkpointRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + id));
        log.info("Restoring checkpoint {} — {} ({} files)", id, checkpoint.getDescription(), checkpoint.getFileCount());

        // For lightweight checkpoints, restore means re-verify files against hashes.
        // This logs discrepancies but doesn't restore content (we only store hashes).
        try {
            ArrayNode files = (ArrayNode) objectMapper.readTree(checkpoint.getFilesJson());
            int verified = 0;
            int changed = 0;
            int missing = 0;

            String workingDir = properties.getCore().getWorkingDirectory();
            Path root = Paths.get(workingDir);

            for (var node : files) {
                String path = node.get("path").asText();
                String expectedHash = node.get("hash").asText();
                Path filePath = root.resolve(path);

                if (!Files.exists(filePath)) {
                    log.warn("Missing file: {}", path);
                    missing++;
                    continue;
                }
                String actualHash = hashFile(filePath);
                if (actualHash.equals(expectedHash)) {
                    verified++;
                } else {
                    log.warn("Changed file: {} (expected: {}, actual: {})", path, expectedHash, actualHash);
                    changed++;
                }
            }

            log.info("Restore verification: {} verified, {} changed, {} missing", verified, changed, missing);
        } catch (Exception e) {
            log.error("Failed to restore checkpoint {}: {}", id, e.getMessage());
            throw new RuntimeException("Restore failed: " + e.getMessage(), e);
        }
    }

    public int prune(int maxSnapshots) {
        List<CheckpointEntity> all = checkpointRepository.findAll().stream()
            .sorted(Comparator.comparing(CheckpointEntity::getCreatedAt).reversed())
            .toList();

        if (all.size() <= maxSnapshots) return 0;

        int toRemove = all.size() - maxSnapshots;
        List<CheckpointEntity> toDelete = all.subList(maxSnapshots, all.size());
        for (CheckpointEntity e : toDelete) {
            checkpointRepository.delete(e);
            log.debug("Pruned checkpoint: {}", e.getId());
        }
        log.info("Pruned {} old checkpoints (max: {})", toRemove, maxSnapshots);
        return toRemove;
    }

    public void remove(UUID id) {
        checkpointRepository.deleteById(id);
        log.info("Removed checkpoint: {}", id);
    }

    private String hashFile(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16); // First 16 chars for lightweight
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private boolean isHidden(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".") || name.startsWith("__")
            || path.toString().contains("/.git/") || path.toString().contains("/.gradle/")
            || path.toString().contains("/build/") || path.toString().contains("/target/")
            || path.toString().contains("/node_modules/");
    }

    public boolean isDangerousCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String lower = command.toLowerCase().trim();
        return lower.matches(".*\\brm\\s+.*") && (lower.contains("-rf") || lower.contains("-fr") || lower.contains("-r "))
            || lower.contains("mkfs")
            || lower.matches(".*\\bdd\\b.*") && lower.contains("if=")
            || lower.contains("mv ") && (lower.contains("/etc/") || lower.contains("/usr/") || lower.contains("/bin/"))
            || lower.contains("chmod 000")
            || lower.contains("chown")
            || lower.contains("> /dev/");
    }
}