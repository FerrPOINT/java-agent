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
 * L32 test: when oldString.equals(newString), the patch should succeed
 * (report "found") instead of incorrectly reporting "not found".
 */
class PatchToolSameStringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void replaceWithSameStringReportsSuccess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("same.txt");
        Files.writeString(file, "hello world hello");
        // oldString == newString — previously this incorrectly returned "not found"
        // because updated.equals(content) was true even though the string was found.
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello\",\"new_string\":\"hello\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("note").asText()).contains("replace");
    }

    @Test
    void replaceAllWithSameStringReportsSuccess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("same_all.txt");
        Files.writeString(file, "foo bar foo");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"foo\",\"replace_all\":true}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("note").asText()).contains("replace all");
    }

    @Test
    void replaceWithSameStringStillFailsWhenNotFound(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("missing.txt");
        Files.writeString(file, "hello world");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"xyz\",\"new_string\":\"xyz\"}",
            null, session);
        assertThat(r.success()).isFalse();
    }
}
