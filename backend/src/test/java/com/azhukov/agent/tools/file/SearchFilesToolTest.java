package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFilesToolTest {

    private final SearchFilesTool tool = new SearchFilesTool();
    private final Session session = Session.create("u", "p", "m");

    @Test
    void searchContentFindsMatch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world\nfoo bar");
        ToolResult r = tool.execute("{\"path\":\"" + dir + "\",\"pattern\":\"hello\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello");
    }

    @Test
    void searchFilesFindsByGlob(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("a.java"));
        Files.createFile(dir.resolve("b.txt"));
        ToolResult r = tool.execute("{\"path\":\"" + dir + "\",\"pattern\":\"*.java\",\"target\":\"files\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.java");
        assertThat(r.content()).doesNotContain("b.txt");
    }

    @Test
    void invalidPathReturnsError() {
        ToolResult r = tool.execute("{\"path\":\"/nonexistent\",\"pattern\":\"x\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void emptyPatternFailsForContentSearch(@TempDir Path dir) {
        ToolResult r = tool.execute("{\"path\":\"" + dir + "\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void invalidRegexReturnsError(@TempDir Path dir) {
        ToolResult r = tool.execute("{\"path\":\"" + dir + "\",\"pattern\":\"[invalid\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    // ── Count mode tests ──────────────────────────────────────────────

    @Test
    void countModeReturnsMatchCountsPerFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world\nhello again\nfoo bar");
        Files.writeString(dir.resolve("b.txt"), "hello once\nbar baz");
        Files.writeString(dir.resolve("c.txt"), "nothing here");
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir + "\",\"pattern\":\"hello\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.txt: 2");
        assertThat(r.content()).contains("b.txt: 1");
        assertThat(r.content()).doesNotContain("c.txt");
    }

    @Test
    void countModeReturnsNoMatchesMessage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world");
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir + "\",\"pattern\":\"nonexistent\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("No matches found");
    }

    @Test
    void countModeRespectsFileGlobFilter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.java"), "hello\nhello");
        Files.writeString(dir.resolve("b.txt"), "hello");
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir + "\",\"pattern\":\"hello\",\"target\":\"content\",\"output_mode\":\"count\",\"fileGlob\":\"*.java\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.java: 2");
        assertThat(r.content()).doesNotContain("b.txt: 1");
    }

    @Test
    void countModeFailsOnEmptyPattern(@TempDir Path dir) {
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir + "\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isFalse();
    }
}
