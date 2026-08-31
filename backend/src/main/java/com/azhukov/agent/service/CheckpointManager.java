package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.persistence.entity.CheckpointFileEntity;
import com.azhukov.agent.persistence.repository.CheckpointFileRepository;
import com.azhukov.agent.persistence.repository.CheckpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Filesystem checkpoint manager — takes snapshots storing file hashes and content,
 * enabling actual file restoration on rollback.
 * Supports snapshot, list, restore, and prune operations.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CheckpointManager {

    private final CheckpointRepository checkpointRepository;
    private final CheckpointFileRepository checkpointFileRepository;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    /** Self-proxy reference so persistCheckpoint/prune get their own @Transactional via Spring AOP. */
    private final ObjectProvider<CheckpointManager> self;

    private static final int MAX_FILES = 10000;

    /** Hermes parity (tools/checkpoint_manager.py DEFAULT_EXCLUDES): build
     *  outputs, caches, VCS dirs, virtualenvs, compiled binaries and media
     *  are never snapshotted. Before this list every checkpoint of the repo
     *  walked .git/build/.gradle — 9999 files, ~128MB stored PER CHECKPOINT. */
    private static final java.util.List<String> EXCLUDED_DIRS = java.util.List.of(
        "node_modules", "dist", "build", "target", "out", ".next", ".nuxt",
        "__pycache__", ".cache", ".pytest_cache", ".mypy_cache", ".ruff_cache",
        "coverage", ".venv", "venv", "env",
        ".git", ".hg", ".svn", ".worktrees", ".gradle", ".idea", ".vscode");
    private static final java.util.List<String> EXCLUDED_SUFFIXES = java.util.List.of(
        ".pyc", ".pyo", ".so", ".dylib", ".dll", ".o", ".a", ".jar", ".class",
        ".exe", ".obj", ".mp4", ".mov", ".mkv", ".webm", ".zip", ".tar", ".gz");
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB per file
    /**
     * Maximum file size for storing content in the DB.
     * Files larger than this are tracked by hash only and cannot be restored.
     */
    private static final long MAX_STORED_CONTENT_SIZE = 512 * 1024; // 512KB


    /** Raw .gitignore lines of the walk root (comments/empty/negations dropped). */
    private static java.util.List<String> readGitignoreRules(Path root) {
        java.util.List<String> rules = new java.util.ArrayList<>();
        Path gi = root.resolve(".gitignore");
        if (Files.isRegularFile(gi)) {
            try {
                for (String line : Files.readAllLines(gi)) {
                    String t = line.strip();
                    if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("!")) {
                        rules.add(t);
                    }
                }
            } catch (Exception ignore) { /* unreadable → no extra excludes */ }
        }
        return rules;
    }

    /** gitignore-pattern check for one root-relative path (dir suffix aware). */
    private static boolean matchesGitignore(String rel, String rule) {
        boolean dirOnly = rule.endsWith("/");
        String body = dirOnly ? rule.substring(0, rule.length() - 1) : rule;
        String regex;
        if (body.startsWith("*.")) {                       // *.jar
            regex = ".*" + java.util.regex.Pattern.quote(body.substring(1)).replace("*", ".*");
        } else if (body.startsWith("/")) {                 // /prototype → root-anchored
            regex = body.substring(1).replace("*", ".*");
        } else if (body.contains("/")) {                   // path-anchored
            regex = "(.*/)?" + body.replace("*", ".*");
        } else {                                            // name anywhere
            regex = "(.*/)?" + body.replace("*", ".*");
        }
        if (dirOnly) {
            regex = regex + "(/.*)?";
        }
        try {
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(regex);
            return pat.matcher(rel).matches() || pat.matcher(rel + "/").matches();
        } catch (Exception e) {
            return false;
        }
    }

    /** Recursive walk that never descends into excluded/gitignored directories. */
    private static void collectFiles(Path root, Path dir, java.util.List<String> ignoreRules, List<Path> out) {
        String relDir = root.relativize(dir).toString().replace('\\', '/');
        if (!relDir.isEmpty()
            && (EXCLUDED_DIRS.contains(dir.getFileName().toString())
                || ignoreRules.stream().anyMatch(r -> matchesGitignore(relDir, r)))) {
            return; // pruned
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (Files.isDirectory(child)) {
                    collectFiles(root, child, ignoreRules, out);
                } else if (Files.isRegularFile(child)) {
                    String rel = root.relativize(child).toString().replace('\\', '/');
                    boolean suffixHit = EXCLUDED_SUFFIXES.stream().anyMatch(s -> child.getFileName().toString().endsWith(s));
                    boolean ignoreHit = ignoreRules.stream().anyMatch(r -> matchesGitignore(rel, r));
                    if (!suffixHit && !ignoreHit) {
                        out.add(child);
                    }
                }
            }
        } catch (Exception ignore) { /* unreadable dir → skip */ }
    }

    /**
     * DTO carrying collected file data from the filesystem phase to the
     * persistence phase, so no filesystem I/O happens inside the transaction.
     */
    private record CollectedFile(String relativePath, String hash, long size, String contentBase64) {}

    /**
     * Takes a checkpoint snapshot: collects file hashes and content from the
     * filesystem (outside any transaction), then persists the result in a
     * short transactional method.
     */
    public CheckpointEntity snapshot(String description) {
        return snapshot(null, description);
    }

    /** Takes a checkpoint owned by the supplied user (null = system/admin). */
    public CheckpointEntity snapshot(String userId, String description) {
        // Phase 1: filesystem collection — NO transaction
        List<CollectedFile> collectedFiles = collectFilesForSnapshot(description);

        // Phase 2: persistence — short @Transactional via Spring proxy
        CheckpointEntity saved = self.getObject().persistCheckpoint(userId, description, collectedFiles);

        // Auto-prune (already @Transactional via proxy)
        self.getObject().prune(properties.getCheckpoints().getMaxSnapshots());

        return saved;
    }

    /**
     * Filesystem collection phase: recursive directory scan, file hashing,
     * and content reading — all performed OUTSIDE any transaction.
     *
     * @param description the checkpoint description (used for logging only)
     * @return list of collected file metadata (hash, size, optional content)
     */
    private List<CollectedFile> collectFilesForSnapshot(String description) {
        String workingDir = properties.getCore().getWorkingDirectory();
        Path root = Paths.get(workingDir);
        log.info("Taking checkpoint snapshot of {} — {}", workingDir, description);

        // Root-relative .gitignore rules + dir pruning: Files.walk with a
        // per-file filter still DESCENDS into excluded dirs (and nested git
        // repos resolve their own root). Walk with pruning instead.
        java.util.List<String> ignoreRules = readGitignoreRules(root);
        List<Path> fileList = new java.util.ArrayList<>();
        collectFiles(root, root, ignoreRules, fileList);

        List<CollectedFile> collected = new java.util.ArrayList<>();

        try (Stream<Path> paths = fileList.stream()) {
            List<Path> filtered = paths
                .filter(p -> !isHidden(p))
                .limit(MAX_FILES)
                .toList();

            for (Path p : filtered) {
                try {
                    long size = Files.size(p);
                    if (size > MAX_FILE_SIZE) {
                        log.debug("Skipping large file: {} ({} bytes)", p, size);
                        continue;
                    }
                    String hash = hashFile(p);
                    String relativePath = root.relativize(p).toString();

                    String contentBase64 = null;
                    if (size <= MAX_STORED_CONTENT_SIZE) {
                        byte[] content = Files.readAllBytes(p);
                        contentBase64 = Base64.getEncoder().encodeToString(content);
                    } else {
                        log.debug("File {} ({} bytes) exceeds content storage limit, storing hash only", relativePath, size);
                    }

                    collected.add(new CollectedFile(relativePath, hash, size, contentBase64));
                } catch (Exception e) {
                    log.debug("Failed to hash file {}: {}", p, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Snapshot walk failed for {}: {}", workingDir, e.getMessage());
        }

        return collected;
    }

    /**
     * Persistence phase: builds the CheckpointEntity and saves it to the
     * database inside a short transaction. No filesystem I/O here.
     *
     * @param description     checkpoint description
     * @param collectedFiles  file metadata collected outside the transaction
     * @return the saved CheckpointEntity
     */
    @Transactional
    public CheckpointEntity persistCheckpoint(String description, List<CollectedFile> collectedFiles) {
        return persistCheckpoint(null, description, collectedFiles);
    }

    @Transactional
    public CheckpointEntity persistCheckpoint(String userId, String description, List<CollectedFile> collectedFiles) {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setUserId(userId);
        entity.setDescription(description);
        entity.setCreatedAt(Instant.now());

        ArrayNode filesArray = objectMapper.createArrayNode();
        long totalSize = 0;

        for (CollectedFile cf : collectedFiles) {
            ObjectNode fileNode = objectMapper.createObjectNode();
            fileNode.put("path", cf.relativePath());
            fileNode.put("hash", cf.hash());
            fileNode.put("size", cf.size());
            filesArray.add(fileNode);

            CheckpointFileEntity fileEntity = new CheckpointFileEntity();
            fileEntity.setCheckpoint(entity);
            fileEntity.setFilePath(cf.relativePath());
            fileEntity.setFileHash(cf.hash());
            fileEntity.setFileSize(cf.size());
            fileEntity.setContentBase64(cf.contentBase64());

            entity.getFiles().add(fileEntity);
            totalSize += cf.size();
        }

        entity.setFileCount(collectedFiles.size());
        entity.setTotalSizeBytes(totalSize);
        try {
            entity.setFilesJson(objectMapper.writeValueAsString(filesArray));
        } catch (Exception e) {
            log.error("Failed to serialize checkpoint files JSON: {}", e.getMessage(), e);
            entity.setFilesJson("[]");
        }

        entity = checkpointRepository.save(entity);
        log.info("Checkpoint created: {} files, {} bytes total — {}", collectedFiles.size(), totalSize, entity.getId());

        return entity;
    }

    public List<CheckpointEntity> list() {
        return checkpointRepository.findAll().stream()
            .sorted(Comparator.comparing(CheckpointEntity::getCreatedAt).reversed())
            .toList();
    }

    /**
     * List checkpoints scoped to a user. Null userId = admin/global access.
     */
    public List<CheckpointEntity> list(String userId) {
        if (userId == null) return list();
        return checkpointRepository.findByUserId(userId).stream()
            .sorted(Comparator.comparing(CheckpointEntity::getCreatedAt).reversed())
            .toList();
    }

    @Transactional
    public void restore(UUID id) {
        CheckpointEntity checkpoint = checkpointRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + id));
        log.info("Restoring checkpoint {} — {} ({} files)", id, checkpoint.getDescription(), checkpoint.getFileCount());

        try {
            String workingDir = properties.getCore().getWorkingDirectory();
            Path root = Paths.get(workingDir);

            // Load stored file content entries
            List<CheckpointFileEntity> storedFiles = checkpointFileRepository.findByCheckpointId(id);
            Map<String, CheckpointFileEntity> contentByPath = new HashMap<>();
            for (CheckpointFileEntity fe : storedFiles) {
                contentByPath.put(fe.getFilePath(), fe);
            }

            // Parse the lightweight file list (hashes) for verification
            ArrayNode files = (ArrayNode) objectMapper.readTree(checkpoint.getFilesJson());
            int restored = 0;
            int verified = 0;
            int changed = 0;
            int missing = 0;
            int skippedNoContent = 0;

            for (var node : files) {
                String path = node.get("path").asText();
                String expectedHash = node.get("hash").asText();
                Path filePath = root.resolve(path);

                CheckpointFileEntity storedFile = contentByPath.get(path);

                if (!Files.exists(filePath)) {
                    // File is missing on disk — try to restore from checkpoint content
                    if (storedFile != null && storedFile.getContentBase64() != null) {
                        Files.createDirectories(filePath.getParent());
                        Files.write(filePath, Base64.getDecoder().decode(storedFile.getContentBase64()));
                        log.info("Restored missing file: {}", path);
                        restored++;
                    } else {
                        log.warn("Missing file, no stored content to restore: {}", path);
                        missing++;
                    }
                    continue;
                }

                String actualHash = hashFile(filePath);
                if (actualHash.equals(expectedHash)) {
                    verified++;
                } else {
                    // File changed — restore from checkpoint content
                    if (storedFile != null && storedFile.getContentBase64() != null) {
                        Files.write(filePath, Base64.getDecoder().decode(storedFile.getContentBase64()));
                        log.info("Restored changed file: {}", path);
                        restored++;
                    } else {
                        log.warn("Changed file, no stored content to restore: {} (expected: {}, actual: {})",
                            path, expectedHash, actualHash);
                        changed++;
                        skippedNoContent++;
                    }
                }
            }

            log.info("Restore complete: {} restored, {} verified, {} changed (no content), {} missing (no content)",
                restored, verified, skippedNoContent, missing);
        } catch (Exception e) {
            log.error("Failed to restore checkpoint {}: {}", id, e.getMessage());
            throw new RuntimeException("Restore failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public int prune(int maxSnapshots) {
        List<CheckpointEntity> all = checkpointRepository.findAll().stream()
            .sorted(Comparator.comparing(CheckpointEntity::getCreatedAt).reversed())
            .toList();

        if (all.size() <= maxSnapshots) return 0;

        int toRemove = all.size() - maxSnapshots;
        List<CheckpointEntity> toDelete = all.subList(maxSnapshots, all.size());
        for (CheckpointEntity e : toDelete) {
            checkpointFileRepository.deleteByCheckpointId(e.getId());
            checkpointRepository.delete(e);
            log.debug("Pruned checkpoint: {}", e.getId());
        }
        log.info("Pruned {} old checkpoints (max: {})", toRemove, maxSnapshots);
        return toRemove;
    }

    @Transactional
    public void remove(UUID id) {
        checkpointFileRepository.deleteByCheckpointId(id);
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
            log.warn("Failed to hash file {}: {}", file, e.getMessage());
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

    public JsonNode diff(UUID left, UUID right, String scope) {
        CheckpointEntity leftCp = checkpointRepository.findById(left)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + left));
        CheckpointEntity rightCp = checkpointRepository.findById(right)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + right));

        ObjectNode result = objectMapper.createObjectNode();
        result.put("left", left.toString());
        result.put("right", right.toString());
        result.put("scope", scope);

        try {
            ArrayNode leftFiles = (ArrayNode) objectMapper.readTree(leftCp.getFilesJson());
            ArrayNode rightFiles = (ArrayNode) objectMapper.readTree(rightCp.getFilesJson());

            Map<String, String> leftMap = new HashMap<>();
            for (JsonNode n : leftFiles) {
                leftMap.put(n.get("path").asText(), n.get("hash").asText());
            }
            Map<String, String> rightMap = new HashMap<>();
            for (JsonNode n : rightFiles) {
                rightMap.put(n.get("path").asText(), n.get("hash").asText());
            }

            Set<String> allPaths = new HashSet<>(leftMap.keySet());
            allPaths.addAll(rightMap.keySet());

            ArrayNode changed = objectMapper.createArrayNode();
            ArrayNode added = objectMapper.createArrayNode();
            ArrayNode removed = objectMapper.createArrayNode();

            for (String path : allPaths) {
                boolean inLeft = leftMap.containsKey(path);
                boolean inRight = rightMap.containsKey(path);
                if (inLeft && inRight) {
                    if (!leftMap.get(path).equals(rightMap.get(path))) {
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("path", path);
                        item.put("leftHash", leftMap.get(path));
                        item.put("rightHash", rightMap.get(path));
                        changed.add(item);
                    }
                } else if (inLeft) {
                    removed.add(path);
                } else {
                    added.add(path);
                }
            }
            result.set("changed", changed);
            result.set("added", added);
            result.set("removed", removed);
            result.put("leftFileCount", leftCp.getFileCount());
            result.put("rightFileCount", rightCp.getFileCount());
        } catch (Exception e) {
            log.error("Failed to diff checkpoints {} {}: {}", left, right, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    public boolean isDangerousCommand(String command) {
        String lower = command.toLowerCase().trim();
        return lower.matches(".*\\brm\\s+.*") && (lower.contains("-rf") || lower.contains("-fr") || lower.contains("-r "))
            || lower.contains("mkfs")
            || lower.matches(".*\\bdd\\b.*") && lower.contains("if=")
            || lower.contains("mv ") && (lower.contains("/etc/") || lower.contains("/usr/") || lower.contains("/bin/"))
            || lower.contains("chmod 000")
            || lower.matches(".*\\bchown\\b.*")
            || lower.contains("> /dev/");
    }
}