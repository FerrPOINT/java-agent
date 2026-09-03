package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
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
 * Tests for WriteFileTool UX improvement:
 * - p9: verification echo — after successful write, include first and last
 *   line of the written content in the response.
 */
class WriteFileVerificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WriteFileTool newTool() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        return new WriteFileTool(props, new com.azhukov.agent.core.security.DefaultFileSafety(props));
    }

    private final Session session = Session.create("u", "p", "m");

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    @Test
    void verificationEchoIncludesFirstAndLastLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        String content = "first line\nmiddle line\nlast line";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n").replace("\"", "\\\"") + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode verification = jsonContent(r).path("verification");
        assertThat(verification.path("first_line").asText()).isEqualTo("first line");
        assertThat(verification.path("last_line").asText()).isEqualTo("last line");
        assertThat(verification.path("line_count").asInt()).isEqualTo(3);
    }

    @Test
    void verificationEchoSingleLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        String content = "only line";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode verification = jsonContent(r).path("verification");
        assertThat(verification.path("first_line").asText()).isEqualTo("only line");
        assertThat(verification.path("last_line").asText()).isEqualTo("only line");
    }

    @Test
    void verificationEchoMultiLineContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.txt");
        String content = "line1\nline2\nline3\nline4\nline5";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode verification = jsonContent(r).path("verification");
        assertThat(verification.path("first_line").asText()).isEqualTo("line1");
        assertThat(verification.path("last_line").asText()).isEqualTo("line5");
    }

    @Test
    void verificationEchoEmptyContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("d.txt");
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"\"}",
            null, session);
        assertThat(r.success()).isTrue();
        JsonNode verification = jsonContent(r).path("verification");
        assertThat(verification.path("first_line").asText()).isEmpty();
        assertThat(verification.path("last_line").asText()).isEmpty();
        assertThat(verification.path("line_count").asInt()).isEqualTo(0);
    }

    @Test
    void verificationEchoStillWritesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("e.txt");
        String content = "hello\nworld";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo(content);
    }

    @Test
    void verificationEchoDoesNotAppearOnFailure() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(props, new com.azhukov.agent.core.security.DefaultFileSafety(props));
        ToolResult r = t.execute(
            "{\"path\":\"/root/.ssh/key\",\"content\":\"x\"}",
            null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).doesNotContain("[verified:");
    }
}
