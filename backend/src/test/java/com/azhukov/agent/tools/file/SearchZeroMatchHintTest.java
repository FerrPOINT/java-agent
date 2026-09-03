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
 * Tests for SearchFilesTool UX improvement:
 * - p8: zero-match hint — when search returns 0 matches, probe case-insensitively
 *   and append a hint if case-insensitive finds matches.
 */
class SearchZeroMatchHintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchFilesTool tool = new SearchFilesTool();
    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void zeroMatchWithCaseInsensitiveMatchesAppendsHint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "Hello World\nHELLO there");
        // "hello" (lowercase) won't match case-sensitively, but case-insensitive finds 2
        ToolResult r = tool.execute(
            "{\"pattern\":\"hello\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).path("warning").asText())
            .contains("0 case-sensitive matches, but 2 case-insensitive matches found")
            .contains("try with case-insensitive flag");
    }

    @Test
    void zeroMatchWithNoCaseInsensitiveMatchesNoHint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world");
        // "xyz" won't match even case-insensitively → no hint
        ToolResult r = tool.execute(
            "{\"pattern\":\"xyz\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).has("warning")).isFalse();
    }

    @Test
    void caseSensitiveMatchNoHint(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello world\nhello again");
        // "hello" matches case-sensitively → no hint needed
        ToolResult r = tool.execute(
            "{\"pattern\":\"hello\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).has("warning")).isFalse();
    }

    @Test
    void zeroMatchHintRespectsFileGlob(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.java"), "Hello World");
        Files.writeString(dir.resolve("b.txt"), "Hello Again");
        // Search only in .java files — case-insensitive should find 1 match in a.java only
        ToolResult r = tool.execute(
            "{\"pattern\":\"hello\",\"path\":\"" + dir + "\",\"fileGlob\":\"*.java\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).path("warning").asText()).contains("1 case-insensitive matches found");
    }

    @Test
    void zeroMatchHintCountsAcrossMultipleFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "Foo Bar");
        Files.writeString(dir.resolve("b.txt"), "FOO baz");
        Files.writeString(dir.resolve("c.txt"), "foo qux");
        // "fOO" (mixed case) won't match case-sensitively, but case-insensitive finds 3
        ToolResult r = tool.execute(
            "{\"pattern\":\"fOO\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).path("warning").asText()).contains("3 case-insensitive matches found");
    }

    @Test
    void zeroMatchHintWithEmptyDir(@TempDir Path dir) throws Exception {
        // Empty dir → no matches at all → no hint
        ToolResult r = tool.execute(
            "{\"pattern\":\"anything\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).has("warning")).isFalse();
    }

    @Test
    void zeroMatchHintWithRegexPattern(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "Test123\ntest456");
        // Case-sensitive regex "TEST" won't match, but case-insensitive will
        ToolResult r = tool.execute(
            "{\"pattern\":\"TEST\\\\d+\",\"path\":\"" + dir + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(jsonContent(r).path("warning").asText()).contains("case-insensitive matches found");
    }
}
