package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodingPostureResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void modeOffNeverCoding() {
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("off", "cli", tempDir.toString())).isFalse();
    }

    @Test
    void modeOnAlwaysCoding() {
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("on", "telegram", tempDir.toString())).isTrue();
    }

    @Test
    void autoDetectsProjectMarker() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void autoDetectsPackageJson() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{\"name\":\"test\"}");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void autoDetectsPyproject() throws IOException {
        Files.writeString(tempDir.resolve("pyproject.toml"), "[project]");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void autoRejectsNotesRepo() throws IOException {
        // A git repo with only .md files is NOT a coding workspace
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("README.md"), "# My Notes");
        Files.writeString(tempDir.resolve("journal.md"), "Dear diary...");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isFalse();
    }

    @Test
    void autoDetectsGitRepoWithCodeFiles() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("README.md"), "# Project");
        Files.writeString(tempDir.resolve("main.py"), "print('hello')");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void autoRejectsTelegramSurface() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins {}");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "telegram", tempDir.toString())).isFalse();
    }

    @Test
    void focusModeSameAsAuto() throws IOException {
        Files.writeString(tempDir.resolve("Cargo.toml"), "[package]");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("focus", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void nullModeDefaultsToAuto() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext(null, "cli", tempDir.toString())).isTrue();
    }

    @Test
    void emptyDirNotCoding() {
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isFalse();
    }

    @Test
    void nonexistentDirNotCoding() {
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", "/nonexistent/path/xyz")).isFalse();
    }

    @Test
    void nullWorkingDirUsesUserDir() {
        CodingPostureResolver r = new CodingPostureResolver();
        // user.dir is /opt/dev/java-agent which has build.gradle → coding
        assertThat(r.isCodingContext("auto", "cli", null)).isTrue();
    }

    @Test
    void blankWorkingDirUsesUserDir() {
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", "")).isTrue();
    }

    @Test
    void agentsMdIsProjectMarker() throws IOException {
        Files.writeString(tempDir.resolve("AGENTS.md"), "# Agent instructions");
        CodingPostureResolver r = new CodingPostureResolver();
        assertThat(r.isCodingContext("auto", "cli", tempDir.toString())).isTrue();
    }

    @Test
    void normalizeModeHandlesNullAndGarbage() {
        assertThat(CodingPostureResolver.normalizeMode(null)).isEqualTo("auto");
        assertThat(CodingPostureResolver.normalizeMode("")).isEqualTo("auto");
        assertThat(CodingPostureResolver.normalizeMode("GARBAGE")).isEqualTo("auto");
        assertThat(CodingPostureResolver.normalizeMode("on")).isEqualTo("on");
        assertThat(CodingPostureResolver.normalizeMode("OFF")).isEqualTo("off");
        assertThat(CodingPostureResolver.normalizeMode("Focus")).isEqualTo("focus");
    }
}