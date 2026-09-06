package com.azhukov.agent.core.agent;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Detects the project context (cwd + git repository root) for session
 * project grouping — Hermes parity for the dashboard project tree
 * (project → repo → cwd lanes).
 *
 * <p>cwd comes from the process working directory (the CLI/bot service
 * launch dir) unless the caller supplies an explicit value (e.g. a cron
 * workdir). git_repo_root is resolved by walking up from cwd looking for a
 * {@code .git} entry. Detection is bounded and failure-tolerant: a missing
 * or unreadable directory yields null repo root (session stays in the
 * "Home / no project" bucket) and never blocks session creation.
 */
@Component
public class ProjectContextDetector {

    /** Hard cap for the upward .git walk — PATH_MAX/4096 protects against path loops. */
    static final int MAX_DEPTH = 64;

    private final Path processCwd;

    public ProjectContextDetector() {
        this.processCwd = Path.of("").toAbsolutePath();
    }

    ProjectContextDetector(Path processCwd) {
        this.processCwd = processCwd;
    }

    /** Result of project detection; both fields nullable. */
    public record ProjectContext(String cwd, String gitRepoRoot) {
        public static ProjectContext none() {
            return new ProjectContext(null, null);
        }
    }

    /**
     * Detect the project context for a new session.
     *
     * @param explicitCwd caller-supplied working directory (cron workdir,
     *                    CLI launch dir); null/blank → process cwd
     */
    public ProjectContext detect(String explicitCwd) {
        String cwd = normalize(explicitCwd != null && !explicitCwd.isBlank()
            ? explicitCwd : String.valueOf(processCwd));
        if (cwd == null) {
            return ProjectContext.none();
        }
        return new ProjectContext(cwd, findGitRoot(cwd));
    }

    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(raw.trim()).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Walk up from {@code cwd} looking for a {@code .git} entry (directory or
     * file — git worktrees use a .git FILE pointing at the real repo).
     * Bounded by {@link #MAX_DEPTH}; any I/O failure aborts quietly with null.
     */
    private String findGitRoot(String cwd) {
        Path dir = Path.of(cwd);
        for (int i = 0; i < MAX_DEPTH && dir != null; i++) {
            if (new File(dir.toFile(), ".git").exists()) {
                return dir.toString();
            }
            dir = dir.getParent();
        }
        return null;
    }
}
