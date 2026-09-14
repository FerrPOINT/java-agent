package com.azhukov.agent.service;

import com.azhukov.agent.core.skill.SkillsHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * WP-5: staged skills hub install/update/uninstall with backup + rollback.
 *
 * <p>Operations run against the PROFILE skills directory (never the DB skill
 * manager): the skill directory is moved aside to {@code .hub-backup/<name>-<ts>}
 * before any write and restored verbatim when a later step fails. Successful
 * operations bump the profile skill revision through the WP-4 runtime
 * registry so prompt caches invalidate and the next turn sees the change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillHubInstaller {

    private final ObjectProvider<ProfileService> profileServiceProvider;
    private final ObjectProvider<ProfileRuntimeRegistry> runtimeRegistryProvider;
    private final ObjectProvider<SkillsHubService> hubServiceProvider;

    public record HubOpResult(boolean ok, String detail, boolean updated, String backupPath) {}

    @FunctionalInterface
    private interface SkillWrite {
        void write() throws Exception;
    }

    public HubOpResult install(String profile, String identifier, boolean overwrite) {
        Path skillsDir;
        Path skillDir;
        try {
            skillsDir = skillsDir(profile);
            skillDir = skillDir(skillsDir, identifier);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new HubOpResult(false, e.getMessage(), false, null);
        }
        boolean exists = Files.isDirectory(skillDir);
        if (exists && !overwrite) {
            return new HubOpResult(false, "skill '" + identifier + "' already exists "
                + "(use overwrite)", false, null);
        }
        return staged(profile, skillsDir, skillDir, "install", () -> {
            SkillsHubService hub = hub();
            SkillsHubService.InstallResult result = hub.install(
                hub.defaultRepoUrl(), identifier, true);
            if (!result.success()) {
                throw new IllegalStateException(result.message());
            }
        });
    }

    public HubOpResult update(String profile, String identifier) {
        Path skillsDir;
        Path skillDir;
        try {
            skillsDir = skillsDir(profile);
            skillDir = skillDir(skillsDir, identifier);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new HubOpResult(false, e.getMessage(), false, null);
        }
        if (!Files.isDirectory(skillDir)) {
            return new HubOpResult(false, "skill '" + identifier + "' is not installed", false, null);
        }
        return staged(profile, skillsDir, skillDir, "update", () -> {
            SkillsHubService hub = hub();
            SkillsHubService.InstallResult result = hub.install(
                hub.defaultRepoUrl(), identifier, true);
            if (!result.success()) {
                throw new IllegalStateException(result.message());
            }
        });
    }

    public HubOpResult uninstall(String profile, String name) {
        Path skillsDir;
        Path skillDir;
        try {
            skillsDir = skillsDir(profile);
            skillDir = skillDir(skillsDir, name);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new HubOpResult(false, e.getMessage(), false, null);
        }
        if (!Files.isDirectory(skillDir)) {
            return new HubOpResult(false, "skill '" + name + "' is not installed", false, null);
        }
        return staged(profile, skillsDir, skillDir, "uninstall", () ->
            deleteRecursive(skillDir));
    }

    // ── staged execution ─────────────────────────────────────────────────

    private HubOpResult staged(String profile, Path skillsDir, Path skillDir,
                               String op, SkillWrite write) {
        String backupPath = null;
        try {
            if (Files.isDirectory(skillDir)) {
                Path backup = backup(skillDir);
                backupPath = backup.toString();
            }
            if ("uninstall".equals(op)) {
                write.write();
            } else {
                if (Files.isDirectory(skillDir)) {
                    deleteRecursive(skillDir); // staged: backup already taken
                }
                write.write();
                if (!Files.isDirectory(skillDir)) {
                    throw new IllegalStateException(op + " did not produce a skill directory");
                }
            }
            bumpRevision(profile, op, skillDir.getFileName().toString());
            log.info("Skills hub {} ok: {} (profile {})", op, skillDir.getFileName(), profile);
            return new HubOpResult(true, "skill " + op + " completed", "update".equals(op), backupPath);
        } catch (Exception e) {
            // rollback: restore the backup verbatim
            String rolledBack = rollback(skillDir, backupPath);
            log.warn("Skills hub {} failed for {} (profile {}): {} — rollback: {}",
                op, skillDir.getFileName(), profile, e.getMessage(), rolledBack);
            return new HubOpResult(false, op + " failed: " + e.getMessage()
                + (rolledBack != null ? "; previous version restored" : ""), false, null);
        }
    }

    private Path backup(Path skillDir) throws IOException {
        Path backupRoot = skillDir.getParent().resolve(".hub-backup");
        Files.createDirectories(backupRoot);
        Path backup = backupRoot.resolve(skillDir.getFileName() + "-"
            + System.currentTimeMillis());
        Files.move(skillDir, backup, StandardCopyOption.ATOMIC_MOVE);
        pruneBackups(backupRoot, skillDir.getFileName().toString());
        return backup;
    }

    private String rollback(Path skillDir, String backupPath) {
        if (backupPath == null) {
            return null;
        }
        try {
            Path backup = Path.of(backupPath);
            if (!Files.isDirectory(backup)) {
                return null;
            }
            if (Files.exists(skillDir)) {
                deleteRecursive(skillDir);
            }
            Files.createDirectories(skillDir.getParent());
            Files.move(backup, skillDir, StandardCopyOption.ATOMIC_MOVE);
            return "ok";
        } catch (IOException e) {
            log.error("Skills hub rollback FAILED — backup remains at {}", backupPath, e);
            return "failed (backup preserved at " + backupPath + ")";
        }
    }

    private static void pruneBackups(Path backupRoot, String skillName) {
        try (Stream<Path> older = Files.list(backupRoot)) {
            older.filter(p -> p.getFileName().toString().startsWith(skillName + "-"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .skip(3)
                .forEach(p -> {
                    try {
                        deleteRecursive(p);
                    } catch (IOException ignored) {
                        // best-effort pruning
                    }
                });
        } catch (IOException ignored) {
            // best-effort pruning
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void bumpRevision(String profile, String op, String skillName) {
        ProfileRuntimeRegistry registry = runtimeRegistryProvider == null
            ? null : runtimeRegistryProvider.getIfAvailable();
        if (registry != null) {
            registry.recordSkillMutation(profile == null || profile.isBlank()
                ? "default" : profile, op + ":" + skillName);
        }
    }

    private Path skillsDir(String profile) {
        ProfileService profiles = profileServiceProvider == null
            ? null : profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            throw new IllegalStateException("profile service is unavailable");
        }
        return profiles.profilePath(profile).resolve("skills").toAbsolutePath().normalize();
    }

    private static Path skillDir(Path skillsDir, String name) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")
            || name.startsWith(".")) {
            throw new IllegalArgumentException("invalid skill name: " + name);
        }
        Path resolved = skillsDir.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(skillsDir)) {
            throw new SecurityException("skill path escapes the skills directory");
        }
        return resolved;
    }

    private SkillsHubService hub() {
        SkillsHubService hub = hubServiceProvider == null ? null : hubServiceProvider.getIfAvailable();
        if (hub == null) {
            throw new IllegalStateException("skills hub service is unavailable");
        }
        return hub;
    }
}
