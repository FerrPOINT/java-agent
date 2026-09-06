package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Project context detection for session project grouping (docs/34 gap 2:
 * persisted cwd/git_repo_root).
 */
class ProjectContextDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("cwd inside a git work tree resolves repo root")
    void detectsRepoRoot() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("my-repo"));
        Files.createDirectories(repo.resolve(".git"));
        Path nested = Files.createDirectories(repo.resolve("backend/src"));

        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx = detector.detect(nested.toString());

        assertThat(ctx.cwd()).isEqualTo(needle(nested));
        assertThat(ctx.gitRepoRoot()).isEqualTo(needle(repo));
    }

    @Test
    @DisplayName("git worktree (.git FILE) is recognized as a repo root")
    void detectsWorktreeFile() throws Exception {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        Path worktree = Files.createDirectories(tempDir.resolve("wt"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + repo.resolve(".git"));

        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx = detector.detect(worktree.toString());

        assertThat(ctx.gitRepoRoot()).isEqualTo(needle(worktree));
    }

    @Test
    @DisplayName("cwd outside any repo yields null repo root (Home bucket)")
    void noRepoYieldsNullRoot() throws Exception {
        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx = detector.detect(tempDir.toString());

        assertThat(ctx.cwd()).isNotNull();
        assertThat(ctx.gitRepoRoot()).isNull();
    }

    @Test
    @DisplayName("missing cwd directory yields no project context (never throws)")
    void missingDirectoryIsTolerated() {
        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx =
            detector.detect(tempDir.resolve("does-not-exist").toString());

        // Invalid explicit path → none(): session creation must never break,
        // and the session lands in the Home bucket.
        assertThat(ctx.cwd()).isNull();
        assertThat(ctx.gitRepoRoot()).isNull();
    }

    @Test
    @DisplayName("blank explicit cwd falls back to process cwd")
    void blankUsesProcessCwd() {
        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx = detector.detect("  ");

        assertThat(ctx.cwd()).isEqualTo(needle(tempDir));
    }

    @Test
    @DisplayName("upward walk is bounded — deep directory without .git returns null")
    void boundedWalk() throws Exception {
        Path deep = tempDir;
        for (int i = 0; i < 3; i++) {
            deep = Files.createDirectories(deep.resolve("lvl" + i));
        }
        ProjectContextDetector detector = new ProjectContextDetector(tempDir);
        ProjectContextDetector.ProjectContext ctx = detector.detect(deep.toString());

        assertThat(ctx.gitRepoRoot()).isNull();
    }

    /** Normalize for filesystem-dependent symlink roots (/tmp → /private/tmp on macOS). */
    private String needle(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }
}
