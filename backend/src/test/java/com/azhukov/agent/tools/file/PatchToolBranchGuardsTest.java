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
 * T1 coverage: the CRLF-normalization fuzzy branch (content is LF-only,
 * old_string carries CRLF so the exact-match path cannot fire) and the
 * already-applied / multi-match guard branches of {@link PatchTool}.
 * Complements the other PatchTool tests without modifying them.
 */
class PatchToolBranchGuardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void crlfOldStringAgainstLfContentPatchesViaNormalization(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lf.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        // old_string uses CRLF; content is pure LF — exact match impossible,
        // strategy 2 (normalized line endings) must fire.
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"two\\r\\nthree\",\"new_string\":\"ZWEI\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).contains("ZWEI");
    }

    @Test
    void alreadyAppliedPatchReportsNoChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applied.txt");
        Files.writeString(file, "target present");
        // new_string already in content, old_string absent → no_change fast path
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"absent old\",\"new_string\":\"target\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("no_change").asBoolean()).isTrue();
        assertThat(json.path("note").asText()).contains("already present");
    }

    @Test
    void multiMatchWithoutReplaceAllIsRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("multi.txt");
        Files.writeString(file, "dup dup dup");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"dup\",\"new_string\":\"one\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("3 times");
        // file untouched
        assertThat(Files.readString(file)).isEqualTo("dup dup dup");
    }

    @Test
    void replaceAllAcceptsMultipleMatches(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("all.txt");
        Files.writeString(file, "dup dup dup");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"dup\",\"new_string\":\"one\",\"replace_all\":true}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("one one one");
    }

    @Test
    void missingFileFailsFast(@TempDir Path dir) throws Exception {
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("ghost.txt") + "\",\"old_string\":\"a\",\"new_string\":\"b\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("File not found");
    }

    @Test
    void missingOldAndNewStringsIsRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ok.txt");
        Files.writeString(file, "content");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"mode\":\"replace\"}",
            null, session);
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("old_string and new_string are required");
    }
}
