package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyOnStopGuardTest {

    private final VerifyOnStopGuard guard = new VerifyOnStopGuard();

    @Test
    void nudgeForCodeFile() {
        String nudge = guard.buildNudge(Set.of("src/main/MyClass.java"), 0, List.of("./gradlew test"));
        assertThat(nudge).isNotNull();
        assertThat(nudge).contains("edited code");
        assertThat(nudge).contains("./gradlew test");
        assertThat(nudge).contains("MyClass.java");
    }

    @Test
    void noNudgeForMarkdownOnly() {
        String nudge = guard.buildNudge(Set.of("README.md", "CHANGELOG.md"), 0, List.of());
        assertThat(nudge).isNull();
    }

    @Test
    void noNudgeForTxtAndLicense() {
        String nudge = guard.buildNudge(Set.of("NOTICE.txt", "LICENSE"), 0, List.of());
        assertThat(nudge).isNull();
    }

    @Test
    void nudgeForMixedCodeAndDoc() {
        String nudge = guard.buildNudge(Set.of("README.md", "src/Main.java"), 0, List.of());
        assertThat(nudge).isNotNull();
        assertThat(nudge).contains("Main.java");
        assertThat(nudge).doesNotContain("README.md");
    }

    @Test
    void noNudgeAfterMaxAttempts() {
        String nudge = guard.buildNudge(Set.of("src/Main.java"), 2, List.of());
        assertThat(nudge).isNull();
    }

    @Test
    void noNudgeForEmptyPaths() {
        String nudge = guard.buildNudge(Set.of(), 0, List.of());
        assertThat(nudge).isNull();
    }

    @Test
    void noNudgeForNullPaths() {
        String nudge = guard.buildNudge(null, 0, List.of());
        assertThat(nudge).isNull();
    }

    @Test
    void nudgeWithoutVerifyCommands() {
        String nudge = guard.buildNudge(Set.of("src/Main.java"), 0, List.of());
        assertThat(nudge).isNotNull();
        assertThat(nudge).contains("No canonical test");
        assertThat(nudge).contains("ad-hoc verification");
    }

    @Test
    void nudgeWithMultipleCommands() {
        String nudge = guard.buildNudge(Set.of("src/Main.java"), 0,
            List.of("./gradlew test", "npm test", "make test", "pytest"));
        assertThat(nudge).contains("./gradlew test");
        assertThat(nudge).contains("npm test");
        assertThat(nudge).contains("make test");
        assertThat(nudge).contains("...");
    }

    @Test
    void nudgeAtSecondAttempt() {
        String nudge = guard.buildNudge(Set.of("src/Main.java"), 1, List.of("pytest"));
        assertThat(nudge).isNotNull();
    }

    @Test
    void nonCodeFilter() {
        assertThat(VerifyOnStopGuard.isNonCodePath("README.md")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("CHANGELOG.md")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("docs/guide.rst")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("data.csv")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("LICENSE")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("CODEOWNERS")).isTrue();
        assertThat(VerifyOnStopGuard.isNonCodePath("src/Main.java")).isFalse();
        assertThat(VerifyOnStopGuard.isNonCodePath("build.gradle")).isFalse();
        assertThat(VerifyOnStopGuard.isNonCodePath("config.yml")).isFalse();
        assertThat(VerifyOnStopGuard.isNonCodePath("app.py")).isFalse();
    }

    @Test
    void filterVerifiablePaths() {
        List<String> result = VerifyOnStopGuard.filterVerifiablePaths(
            Set.of("README.md", "src/Main.java", "LICENSE", "build.gradle", "docs/guide.rst"));
        assertThat(result).containsExactly("build.gradle", "src/Main.java");
    }

    @Test
    void nudgeContainsChangedPaths() {
        String nudge = guard.buildNudge(Set.of("src/a.py", "src/b.py", "src/c.py"), 0, List.of("pytest"));
        assertThat(nudge).contains("src/a.py");
        assertThat(nudge).contains("src/b.py");
        assertThat(nudge).contains("src/c.py");
    }

    @Test
    void nudgeTruncatesLongPathList() {
        Set<String> paths = new java.util.HashSet<>();
        for (int i = 0; i < 15; i++) paths.add("src/file" + i + ".java");
        String nudge = guard.buildNudge(paths, 0, List.of("pytest"));
        assertThat(nudge).contains("... and 7 more");
    }
}