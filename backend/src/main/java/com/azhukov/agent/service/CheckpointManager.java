package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.SecureFileAccess;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

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
    private static List<String> readGitignoreRules(SecureFileAccess.Directory root) {
        try {
            byte[] bytes = readFile(root, Path.of(".gitignore"));
            if (bytes != null) {
                return new String(bytes, StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
                    .toList();
            }
        } catch (IOException e) {
            log.debug("Skipping unreadable .gitignore", e);
        }
        return List.of();
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

    private static int collectFiles(SecureFileAccess.Directory directory, Path relative,
                                     List<String> ignoreRules, List<CollectedFile> out, int remaining) {
        for (Path entry : directory.entries()) {
            if (remaining == 0) break;
            Path name = entry.getFileName();
            Path path = relative.resolve(name);
            String pathText = path.toString();
            // Keep the path length supported by the historical Linux pathname-based walker.
            if (pathText.getBytes(StandardCharsets.UTF_8).length >= 4096
                || ignoreRules.stream().anyMatch(rule -> matchesGitignore(pathText, rule))) {
                continue;
            }
            try {
                var attributes = directory.attributes(name);
                if (attributes.isDirectory()) {
                    if (!EXCLUDED_DIRS.contains(name.toString())) {
                        try (var child = directory.openDirectory(name, false)) {
                            remaining = collectFiles(child, path, ignoreRules, out, remaining);
                        }
                    }
                } else if (attributes.isRegularFile() && !isHidden(path)
                    && EXCLUDED_SUFFIXES.stream().noneMatch(suffix -> name.toString().endsWith(suffix))) {
                    remaining--;
                    var file = inspectFile(directory, name, true);
                    if (file != null) {
                        out.add(new CollectedFile(pathText, file.hash(), file.size(), file.contentBase64()));
                    }
                }
            } catch (IOException e) {
                log.debug("Skipping unreadable checkpoint entry {}", path);
            }
        }
        return remaining;
    }

    private record FileFingerprint(String hash, long size, String contentBase64) {}

    private static FileFingerprint inspectFile(SecureFileAccess.Directory directory, Path name, boolean storeContent)
            throws IOException {
        try (var channel = directory.openRead(name)) {
            long initialSize = channel.size();
            if (initialSize > MAX_FILE_SIZE) return null;
            var digest = sha256();
            var content = storeContent ? new ByteArrayOutputStream() : null;
            var input = Channels.newInputStream(channel);
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, MAX_FILE_SIZE + 1 - size))) != -1) {
                size += count;
                if (size > MAX_FILE_SIZE) return null;
                digest.update(buffer, 0, count);
                if (size > MAX_STORED_CONTENT_SIZE) content = null;
                if (content != null) content.write(buffer, 0, count);
            }
            return new FileFingerprint(HexFormat.of().formatHex(digest.digest(), 0, 8), size,
                content == null ? null : Base64.getEncoder().encodeToString(content.toByteArray()));
        }
    }

    private static byte[] readFile(SecureFileAccess.Directory directory, Path name) throws IOException {
        try (var channel = directory.openRead(name)) {
            if (channel.size() > MAX_FILE_SIZE) return null;
            byte[] bytes = Channels.newInputStream(channel).readNBytes((int) MAX_FILE_SIZE + 1);
            return bytes.length > MAX_FILE_SIZE ? null : bytes;
        }
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
        Path root = Paths.get(workingDir).toAbsolutePath().normalize();
        log.info("Taking checkpoint snapshot of {} — {}", workingDir, description);
        List<CollectedFile> collected = new ArrayList<>();
        try (var directory = SecureFileAccess.openDirectory(root)) {
            collectFiles(directory, Path.of(""), readGitignoreRules(directory), collected, MAX_FILES);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not snapshot workspace " + root, e);
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
        return checkpointRepository.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
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
        restoreCheckpoint(id, checkpoint);
    }

    @Transactional
    public void restore(UUID id, String ownerId) {
        if (ownerId == null) {
            restore(id);
            return;
        }
        CheckpointEntity checkpoint = checkpointRepository.findByIdAndUserId(id, ownerId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + id));
        restoreCheckpoint(id, checkpoint);
    }

    private void restoreCheckpoint(UUID id, CheckpointEntity checkpoint) {
        log.info("Restoring checkpoint {} — {} ({} files)", id, checkpoint.getDescription(), checkpoint.getFileCount());

        List<Path> restored = new ArrayList<>();
        Path root = Paths.get(properties.getCore().getWorkingDirectory()).toAbsolutePath().normalize();
        try {
            ArrayNode files = (ArrayNode) objectMapper.readTree(checkpoint.getFilesJson());
            // Validate the complete historical manifest before making any changes.
            for (var node : files) {
                checkpointPath(node.path("path").asText());
            }
            Map<String, CheckpointFileEntity> contentByPath = new HashMap<>();
            for (CheckpointFileEntity file : checkpointFileRepository.findByCheckpointId(id)) {
                checkpointPath(file.getFilePath());
                contentByPath.put(file.getFilePath(), file);
            }

            try (var directory = SecureFileAccess.openDirectory(root)) {
                for (var node : files) {
                    Path path = checkpointPath(node.path("path").asText());
                    CheckpointFileEntity stored = contentByPath.get(path.toString());
                    String content = stored == null ? null : stored.getContentBase64();
                    if (path.getParent() == null) {
                        restoreFile(directory, path, node.path("hash").asText(), content, restored);
                    } else {
                        try (var parent = directory.openDirectory(path.getParent(), content != null)) {
                            restoreFile(parent, path, node.path("hash").asText(), content, restored);
                        } catch (NoSuchFileException missing) {
                            if (content != null) throw missing;
                            log.warn("Missing file, no stored content to restore: {}", path);
                        }
                    }
                }
            }
            log.info("Checkpoint restore complete: {} files restored", restored.size());
        } catch (Exception e) {
            log.error("Failed to restore checkpoint {} after restoring {}: {}", id, restored, e.getMessage());
            throw new IllegalStateException("Restore failed after restoring " + restored + ": " + e.getMessage(), e);
        }
    }

    private static Path checkpointPath(String raw) {
        Path path = Path.of(raw);
        if (raw.isEmpty() || path.isAbsolute() || path.startsWith("..")
            || !path.normalize().toString().equals(raw)) {
            throw new IllegalArgumentException("Invalid checkpoint path: " + raw);
        }
        return path;
    }

    private static void restoreFile(SecureFileAccess.Directory parent, Path path, String expectedHash,
                                    String content, List<Path> restored) throws IOException {
        Path name = path.getFileName();
        FileFingerprint current = null;
        try {
            current = inspectFile(parent, name, false);
        } catch (NoSuchFileException missing) {
            // A stored file can be recreated.
        }
        if (current != null && expectedHash.equals(current.hash())) return;
        if (content == null) {
            log.warn("File differs, no stored content to restore: {}", path);
            return;
        }
        parent.replaceFile(name, Base64.getDecoder().decode(content));
        restored.add(path);
    }

    @Transactional
    public int prune(int maxSnapshots) {
        List<CheckpointEntity> all = checkpointRepository.findAll(org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));

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

    @Transactional
    public void remove(UUID id, String ownerId) {
        if (ownerId == null) {
            remove(id);
            return;
        }
        CheckpointEntity checkpoint = checkpointRepository.findByIdAndUserId(id, ownerId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + id));
        checkpointFileRepository.deleteByCheckpointId(id);
        checkpointRepository.delete(checkpoint);
        log.info("Removed checkpoint: {}", id);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isHidden(Path path) {
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
        return diffCheckpoints(left, right, scope, leftCp, rightCp);
    }

    public JsonNode diff(UUID left, UUID right, String scope, String ownerId) {
        if (ownerId == null) {
            return diff(left, right, scope);
        }
        CheckpointEntity leftCp = checkpointRepository.findByIdAndUserId(left, ownerId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + left));
        CheckpointEntity rightCp = checkpointRepository.findByIdAndUserId(right, ownerId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + right));
        return diffCheckpoints(left, right, scope, leftCp, rightCp);
    }

    private JsonNode diffCheckpoints(UUID left, UUID right, String scope,
                                     CheckpointEntity leftCp, CheckpointEntity rightCp) {

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