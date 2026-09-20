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
 * T1 coverage: the fuzzy fallback strategies of {@link PatchTool#execute}
 * (whitespace-trim, CRLF normalization, collapsed whitespace,
 * case-insensitive match, trailing whitespace per line, BOM stripping,
 * tabs-to-spaces, empty-lines-removed). Each test drives a real temp file
 * through one strategy so the fuzzy branches — not just the exact-match
 * path — are exercised. Complements PatchToolTest / PatchToolSameStringTest
 * without modifying them.
 */
class PatchToolFuzzyStrategiesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void trimmedWhitespaceFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trim.txt");
        Files.writeString(file, "alpha beta gamma");
        // old_string has surrounding spaces; trimmed match exists in content
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"  alpha  \",\"new_string\":\"DELTA\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("DELTA beta gamma");
    }

    @Test
    void crlfNormalizationFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("crlf.txt");
        Files.writeString(file, "one\r\ntwo\r\nthree");
        // old_string carries CRLF too; normalized content contains the LF form
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"two\\r\\nthree\",\"new_string\":\"ZWEI\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("ZWEI");
    }

    @Test
    void collapsedWhitespaceFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("collapse.txt");
        Files.writeString(file, "value with many spaces and more");
        // old_string has whitespace runs (collapsed != original); collapsed forms match
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"value  with  many  spaces\",\"new_string\":\"compact\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("compact");
    }

    @Test
    void caseInsensitiveFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("case.txt");
        Files.writeString(file, "Config Value = 1");
        // exact-case match does not exist, case-insensitive does
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"CONFIG VALUE\",\"new_string\":\"cfg\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("cfg = 1");
    }

    @Test
    void trailingWhitespacePerLineFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("trail.txt");
        Files.writeString(file, "line one\nline two\n");
        // old_string carries trailing spaces per line; content does not
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"line one   \\nline two  \",\"new_string\":\"row\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("row");
    }

    @Test
    void bomStrippedFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bom.txt");
        Files.writeString(file, "\uFEFFneedle in haystack");
        // old_string matches only after the BOM is stripped from content
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"needle in haystack\",\"new_string\":\"found\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("found");
    }

    @Test
    void tabsToSpacesFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tabs.txt");
        // content uses 4-space indentation where old_string uses tabs
        Files.writeString(file, "def f():\n    return 1\n");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"def f():\\n\\treturn 1\",\"new_string\":\"spaced\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("spaced");
    }

    @Test
    void emptyLinesRemovedFallbackPatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.txt");
        Files.writeString(file, "header\nfooter\n");
        // old_string has blank lines inside; content has none
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"header\\n\\nfooter\",\"new_string\":\"joined\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("joined");
    }

    @Test
    void unmatchedStringListsAllStrategiesInError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nomatch.txt");
        Files.writeString(file, "nothing relevant");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"absent\",\"new_string\":\"x\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("fuzzy strategies");
    }
}
