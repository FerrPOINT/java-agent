package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodingContextDetectorTest {

    @TempDir
    Path tempDir;

    private final CodingContextDetector detector = new CodingContextDetector();

    @Test
    void detect_javaMavenProject() throws IOException {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");

        CodingContextDetector.CodingContext ctx = detector.detect(tempDir.toString());

        assertThat(ctx.language()).isEqualTo("Java");
        assertThat(ctx.buildTool()).isEqualTo("Maven");
        assertThat(ctx.isGitRepo()).isTrue();
    }

    @Test
    void detect_nodeProjectWithReact() throws IOException {
        Files.writeString(tempDir.resolve("package.json"),
            "{\"name\":\"test\",\"dependencies\":{\"react\":\"^18.0.0\"}}");

        CodingContextDetector.CodingContext ctx = detector.detect(tempDir.toString());

        assertThat(ctx.language()).contains("JavaScript").contains("TypeScript");
        assertThat(ctx.framework()).contains("react");
        assertThat(ctx.buildTool()).isEqualTo("npm");
    }

    @Test
    void detect_pythonProject() throws IOException {
        Files.writeString(tempDir.resolve("requirements.txt"), "flask==2.0.0\nrequests==2.28.0");

        CodingContextDetector.CodingContext ctx = detector.detect(tempDir.toString());

        assertThat(ctx.language()).isEqualTo("Python");
        assertThat(ctx.buildTool()).isEqualTo("pip");
    }

    @Test
    void detect_emptyDir_returnsEmpty() {
        CodingContextDetector.CodingContext ctx = detector.detect(tempDir.toString());

        assertThat(ctx.language()).isNull();
        assertThat(ctx.framework()).isNull();
        assertThat(ctx.buildTool()).isNull();
        assertThat(ctx.isGitRepo()).isFalse();
    }
}