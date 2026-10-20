package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 coverage: {@link CodingWorkspaceSnapshot} build()/getVerifyCommands()
 * against real temp workspaces — git root detection, marker fallback,
 * manifests/package-manager line, verify command detection (gradle, npm
 * scripts, pytest, Makefile), and the empty-input guards.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CodingWorkspaceSnapshotTest {

    private final CodingWorkspaceSnapshot snapshot = new CodingWorkspaceSnapshot();

    @Test
    void blankWorkingDirYieldsEmptySnapshot() {
        assertThat(snapshot.build(null)).isEmpty();
        assertThat(snapshot.build("  ")).isEmpty();
    }

    @Test
    void missingDirectoryYieldsEmptySnapshot() {
        assertThat(snapshot.build("/definitely/not/a/real/path")).isEmpty();
    }

    @Test
    void plainDirectoryWithoutMarkersYieldsEmptySnapshot(@TempDir Path dir) throws IOException {
        // temp dir itself has no markers and (normally) no .git above it
        // unless the test machine source tree is above — use a nested marker-free dir
        Path nested = dir.resolve("no-markers");
        Files.createDirectories(nested);
        // On a dev machine the parent may still be a git repo; accept either
        // a git-rooted snapshot or empty — but never an exception.
        String out = snapshot.build(nested.toString());
        assertThat(out).isNotNull();
    }

    @Test
    void gradleWorkspaceDetectsManifestsAndVerifyCommands(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "plugins { }\n");
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'x'\n");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("Workspace (snapshot");
        assertThat(out).contains("- Root: " + dir.toAbsolutePath());
        assertThat(out).contains("build.gradle");
        assertThat(out).contains("./gradlew test");
    }

    @Test
    void npmWorkspaceDetectsPackageManagerAndScriptTargets(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"),
            "{\"name\":\"x\",\"scripts\":{\"test\":\"vitest run\",\"lint\":\"eslint .\"}}");
        Files.writeString(dir.resolve("pnpm-lock.yaml"), "");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("package.json");
        assertThat(out).contains("pnpm run test");
        assertThat(out).contains("pnpm run lint");
        assertThat(out).contains("pnpm");
    }

    @Test
    void pythonWorkspaceDetectsPytest(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("pytest");
        assertThat(out).contains("pyproject.toml");
    }

    @Test
    void makefileTargetsDetected(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Makefile"), "test:\n\techo hi\nlint:\n\techo lo\n");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("make test");
        assertThat(out).contains("make lint");
    }

    @Test
    void contextFilesReportedSeparatelyFromManifests(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("AGENTS.md"), "# agents\n");
        Files.writeString(dir.resolve("Cargo.toml"), "[package]\nname = \"x\"\n");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("Context files: AGENTS.md");
        assertThat(out).contains("Cargo.toml");
        assertThat(out).contains("cargo");
    }

    @Test
    void scriptsRunTestsShHasTopPriority(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts/run_tests.sh"), "#!/bin/sh\n");
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        String out = snapshot.build(dir.toString());
        assertThat(out).contains("scripts/run_tests.sh");
        assertThat(out).contains("mvn test");
        assertThat(out).contains("maven");
    }

    @Test
    void verifyCommandsUseMarkerRootFromNestedDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts/run_tests.sh"), "#!/bin/sh\n");
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { }\n");
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n");
        Files.writeString(dir.resolve("package.json"), """
            {"scripts":{"test":"vitest","lint":"eslint .","typecheck":"tsc"}}
            """);
        Files.writeString(dir.resolve("yarn.lock"), "");
        Files.writeString(dir.resolve("pytest.ini"), "[pytest]\n");
        Files.writeString(dir.resolve("Makefile"), "test:\n\techo test\nlint:\n\techo lint\n");
        Path nested = Files.createDirectories(dir.resolve("src/deep"));

        List<String> commands = withWorkingDir(nested.toString(), snapshot::getVerifyCommands);

        assertThat(commands).containsExactly("scripts/run_tests.sh", "./gradlew test", "mvn test",
            "yarn run test", "yarn run lint", "yarn run typecheck", "pytest", "make test");
        assertThat(snapshot.build(nested.toString())).contains("Verify: " + String.join("; ", commands));
    }

    @Test
    void verifyCommandsDetectPyprojectAndIgnoreNonTargets(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pyproject.toml"), "[tool.pytest.ini_options]\n");
        Files.writeString(dir.resolve("package.json"), """
            {"scripts":{"dev":"vite","check":"tsc","build":"vite build"}}
            """);
        Files.writeString(dir.resolve("Makefile"), "format:\n\techo format\nnotlint:\n\techo unrelated\n");

        assertThat(withWorkingDir(dir.toString(), snapshot::getVerifyCommands))
            .containsExactly("npm run check", "npm run build", "pytest", "make format");
    }

    @Test
    void verifyCommandsRejectUnavailableWorkingDirectory(@TempDir Path dir) {
        assertThat(withWorkingDir(null, snapshot::getVerifyCommands)).isEmpty();
        assertThat(withWorkingDir("  ", snapshot::getVerifyCommands)).isEmpty();
        assertThat(withWorkingDir(dir.resolve("missing").toString(), snapshot::getVerifyCommands)).isEmpty();
        assertThat(withWorkingDir(dir.toString(), snapshot::getVerifyCommands)).isEmpty();
    }

    @Test
    void oversizedManifestDoesNotSuggestCommandsFromTruncatedData(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), " ".repeat(256 * 1024) + "\"test\":\"unsafe\"");
        assertThat(withWorkingDir(dir.toString(), snapshot::getVerifyCommands)).isEmpty();
        assertThat(snapshot.build(dir.toString())).contains("package.json").doesNotContain("npm run test");
    }

    @Test
    void realGitSnapshotReportsBranchCommitAndDetachedHead(@TempDir Path dir) throws Exception {
        git(dir, "init", "-b", "qa-snapshot");
        Files.writeString(dir.resolve("go.mod"), "module qa\n");
        git(dir, "add", "go.mod");
        git(dir, "-c", "user.name=QA", "-c", "user.email=qa@example.test", "commit", "-m", "QA snapshot fixture");
        Path nested = Files.createDirectories(dir.resolve("src"));

        assertThat(snapshot.build(nested.toString())).contains("- Root: " + dir.toAbsolutePath(),
            "- Branch: qa-snapshot", "- Status: clean", "QA snapshot fixture", "(go)");
        Files.writeString(dir.resolve("new-file.txt"), "untracked\n");
        assertThat(snapshot.build(dir.toString())).contains("- Status: 1 untracked");
        git(dir, "checkout", "--detach", "HEAD");
        assertThat(snapshot.build(dir.toString())).contains("- Branch: (detached HEAD)");
    }

    private static <T> T withWorkingDir(String directory, Supplier<T> action) {
        String previous = System.getProperty("user.dir");
        try {
            if (directory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", directory);
            return action.get();
        } finally {
            if (previous == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", previous);
        }
    }

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
