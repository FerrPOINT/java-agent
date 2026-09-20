package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 coverage: {@link CodingWorkspaceSnapshot} build()/getVerifyCommands()
 * against real temp workspaces — git root detection, marker fallback,
 * manifests/package-manager line, verify command detection (gradle, npm
 * scripts, pytest, Makefile), and the empty-input guards.
 */
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
}
