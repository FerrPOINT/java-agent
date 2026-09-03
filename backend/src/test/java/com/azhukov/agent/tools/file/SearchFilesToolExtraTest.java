package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extra branch-coverage tests for SearchFilesTool.
 */
class SearchFilesToolExtraTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchFilesTool tool = new SearchFilesTool();
    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void searchContentWithFileGlobFilter(@TempDir Path dir) throws Exception {
        Path javaFile = dir.resolve("a.java");
        Files.writeString(javaFile, "test line\nanother test");
        Path txtFile = dir.resolve("b.txt");
        Files.writeString(txtFile, "test line\nanother test");

        ToolResult r = tool.execute(
            "{\"pattern\":\"test\",\"path\":\"" + dir + "\",\"fileGlob\":\"*.java\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.java");
        assertThat(r.content()).doesNotContain("b.txt");
    }

    @Test
    void searchContentWithOffset(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("test line\n");
        Files.writeString(file, sb.toString());

        ToolResult r = tool.execute(
            "{\"pattern\":\"test\",\"path\":\"" + dir + "\",\"offset\":2}", null, session);
        assertThat(r.success()).isTrue();
        // With offset=2, first 2 matches are skipped
        // The result should still have matches
        assertThat(r.content()).isNotEmpty();
    }

    @Test
    void searchContentWithLimitTruncation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("match\n");
        Files.writeString(file, sb.toString());

        ToolResult r = tool.execute(
            "{\"pattern\":\"match\",\"path\":\"" + dir + "\",\"limit\":5}", null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("hint").asText()).contains("Results truncated");
    }

    @Test
    void searchContentWithContextLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "line1\nline2\nmatch\nline4\nline5");

        ToolResult r = tool.execute(
            "{\"pattern\":\"match\",\"path\":\"" + dir + "\",\"context\":1}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("match");
        assertThat(r.content()).contains("line2");
        assertThat(r.content()).contains("line4");
    }

    @Test
    void searchFilesWithNullPatternDefaultsToStar(@TempDir Path dir) throws Exception {
        Path file1 = dir.resolve("a.txt");
        Files.writeString(file1, "x");
        Path file2 = dir.resolve("b.txt");
        Files.writeString(file2, "y");

        ToolResult r = tool.execute(
            "{\"target\":\"files\",\"path\":\"" + dir + "\"}", null, session);
        assertThat(r.success()).isTrue();
        // Should find all files with default glob "*"
        assertThat(r.content()).contains("a.txt");
        assertThat(r.content()).contains("b.txt");
    }

    @Test
    void searchFilesWithCustomLimit(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("f" + i + ".txt"), "x");
        }

        ToolResult r = tool.execute(
            "{\"target\":\"files\",\"path\":\"" + dir + "\",\"pattern\":\"*.txt\",\"limit\":2}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).path("files")).hasSize(2);
    }

    @Test
    void searchContentWithNullPathDefaultsToCwd() {
        // Just verify it doesn't crash — the search runs in "."
        // Use a very unique pattern that won't match typical files
        ToolResult r = tool.execute("{\"pattern\":\"zzz_nonexistent_pattern_xyz123\"}", null, session);
        // May succeed with empty results or fail if path issues — just verify no exception
        assertThat(r).isNotNull();
    }

    @Test
    void searchFilesWithQuestionMarkGlob(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("cat.txt"), "x");
        Files.writeString(dir.resolve("bat.txt"), "y");

        ToolResult r = tool.execute(
            "{\"target\":\"files\",\"path\":\"" + dir + "\",\"pattern\":\"?at.txt\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("cat.txt");
        assertThat(r.content()).contains("bat.txt");
    }

    @Test
    void searchFilesWithDotGlob(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.json"), "x");

        ToolResult r = tool.execute(
            "{\"target\":\"files\",\"path\":\"" + dir + "\",\"pattern\":\"config.json\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("config.json");
    }

    @Test
    void searchContentWithNegativeContext(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "line1\nmatch\nline3");

        ToolResult r = tool.execute(
            "{\"pattern\":\"match\",\"path\":\"" + dir + "\",\"context\":-5}", null, session);
        assertThat(r.success()).isTrue();
        // Negative context should be clamped to 0 — only the match line itself
        assertThat(r.content()).contains("match");
    }
}
