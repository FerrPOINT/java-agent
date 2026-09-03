package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFilesToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchFilesTool tool = new SearchFilesTool();
    private final Session session = Session.create("u", "p", "m");

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        ToolResult r = tool.execute("{", null, session);

        assertThat(r.success()).isFalse();
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(r.error()).isEqualTo(json.path("error").asText());
    }

    @Test
    void searchContentFindsMatch(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world\nfoo bar");
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"hello\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello");
    }

    @Test
    void defaultSearchPathUsesSessionWorkdirLikeHermes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "needle");
        Session cwdSession = session.withMetadata(TerminalTool.META_WORKDIR, dir.toString());

        ToolResult r = tool.execute("{\"pattern\":\"needle\",\"target\":\"content\"}", null, cwdSession);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.txt");
        assertThat(r.content()).contains("1|needle");
    }

    @Test
    void searchContentSkipsSensitiveFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "SECRET_TOKEN=leak");
        Files.writeString(dir.resolve("visible.txt"), "SECRET_TOKEN=public fixture");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"SECRET_TOKEN\",\"target\":\"content\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("visible.txt");
        assertThat(r.content()).doesNotContain(".env");
        assertThat(r.content()).doesNotContain("leak");
    }

    @Test
    void searchContentSkipsBinaryFilesWithoutFailingWholeSearch(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("payload.bin"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0, 1, 2});
        Files.writeString(dir.resolve("visible.txt"), "needle in text");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"needle\",\"target\":\"content\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("visible.txt");
        assertThat(r.content()).doesNotContain("payload.bin");
    }

    @Test
    void searchContentSkipsInvalidUtf8FilesWithoutFailingWholeSearch(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("broken.txt"), new byte[] {(byte) 0xC3, 0x28});
        Files.writeString(dir.resolve("visible.txt"), "needle in text");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"needle\",\"target\":\"content\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("visible.txt");
        assertThat(r.content()).doesNotContain("Search failed");
    }

    @Test
    void filesOnlyModeReturnsMatchingPathsWithoutLineContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("alpha.txt"), "needle private value");
        Files.writeString(dir.resolve("beta.txt"), "needle public value");
        Files.writeString(dir.resolve("gamma.txt"), "nothing here");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"needle\",\"target\":\"content\",\"output_mode\":\"files_only\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("alpha.txt");
        assertThat(r.content()).contains("beta.txt");
        assertThat(r.content()).doesNotContain("gamma.txt");
        assertThat(r.content()).doesNotContain("private value");
        assertThat(r.content()).doesNotContain("public value");
        assertThat(r.content()).doesNotContain("1|needle");
    }

    @Test
    void searchFilesSkipsSensitiveFileNames(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "TOKEN=secret");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\".env\",\"target\":\"files\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).doesNotContain(".env");
    }

    @Test
    void searchFilesFindsByGlob(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("a.java"));
        Files.createFile(dir.resolve("b.txt"));
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"*.java\",\"target\":\"files\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.java");
        assertThat(r.content()).doesNotContain("b.txt");
    }

    @Test
    void searchFilesAcceptsHermesFindAlias(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("a.java"));
        Files.writeString(dir.resolve("b.txt"), "a.java appears only in content");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"*.java\",\"target\":\"find\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.java");
        assertThat(r.content()).doesNotContain("b.txt");
        assertThat(r.content()).doesNotContain("PatternSyntaxException");
    }

    @Test
    void searchFilesAcceptsHermesGrepAlias(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "needle");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"needle\",\"target\":\"grep\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.txt");
        assertThat(r.content()).contains("1|needle");
    }

    @Test
    void searchFilesDoesNotReturnDirectories(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("matched.txt"));
        Files.writeString(dir.resolve("real.txt"), "content");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"*.txt\",\"target\":\"files\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("real.txt");
        assertThat(r.content()).doesNotContain("matched.txt");
    }

    @Test
    void searchFilesTreatsNegativeOffsetAsZero(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("a.txt"));

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"*.txt\",\"target\":\"files\",\"offset\":-1}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("a.txt");
    }

    @Test
    void invalidPathReturnsError() {
        ToolResult r = tool.execute("{\"path\":\"/nonexistent\",\"pattern\":\"x\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void emptyPatternFailsForContentSearch(@TempDir Path dir) {
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir) + "\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void invalidRegexReturnsError(@TempDir Path dir) {
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"[invalid\",\"target\":\"content\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    // ── Count mode tests ──────────────────────────────────────────────

    @Test
    void countModeReturnsMatchCountsPerFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world\nhello again\nfoo bar");
        Files.writeString(dir.resolve("b.txt"), "hello once\nbar baz");
        Files.writeString(dir.resolve("c.txt"), "nothing here");
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"hello\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("total_count").asInt()).isEqualTo(3);
        assertThat(json.path("counts").path("a.txt").asInt()).isEqualTo(2);
        assertThat(json.path("counts").path("b.txt").asInt()).isEqualTo(1);
        assertThat(json.path("counts").has("c.txt")).isFalse();
    }

    @Test
    void countModeReturnsNoMatchesMessage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world");
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"nonexistent\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("No matches found");
    }

    @Test
    void countModeRespectsFileGlobFilter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.java"), "hello\nhello");
        Files.writeString(dir.resolve("b.txt"), "hello");
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"hello\",\"target\":\"content\",\"output_mode\":\"count\",\"fileGlob\":\"*.java\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("counts").path("a.java").asInt()).isEqualTo(2);
        assertThat(json.path("counts").has("b.txt")).isFalse();
    }

    @Test
    void contentSearchFileGlobCanMatchRelativePath(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main"));
        Files.createDirectories(dir.resolve("src/test"));
        Files.writeString(dir.resolve("src/main/App.java"), "needle");
        Files.writeString(dir.resolve("src/test/AppTest.java"), "needle");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"pattern\":\"needle\",\"target\":\"content\",\"file_glob\":\"src/main/*.java\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("src");
        assertThat(r.content()).contains("App.java");
        assertThat(r.content()).doesNotContain("AppTest.java");
    }

    @Test
    void countModeFailsOnEmptyPattern(@TempDir Path dir) {
        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(dir) + "\",\"target\":\"content\",\"output_mode\":\"count\"}",
            null, session);
        assertThat(r.success()).isFalse();
    }
}
