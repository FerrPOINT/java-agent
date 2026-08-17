package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
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

    private WriteFileTool newTool() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        return new WriteFileTool(props);
    }

    private final Session session = Session.create("u", "p", "m");

    @Test
    void verificationEchoIncludesFirstAndLastLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        String content = "first line\nmiddle line\nlast line";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n").replace("\"", "\\\"") + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[verified:");
        assertThat(r.content()).contains("first line: \"first line\"");
        assertThat(r.content()).contains("last line: \"last line\"");
    }

    @Test
    void verificationEchoSingleLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        String content = "only line";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[verified: first line: \"only line\", last line: \"only line\"]");
    }

    @Test
    void verificationEchoMultiLineContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.txt");
        String content = "line1\nline2\nline3\nline4\nline5";
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("first line: \"line1\"");
        assertThat(r.content()).contains("last line: \"line5\"");
    }

    @Test
    void verificationEchoEmptyContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("d.txt");
        ToolResult r = newTool().execute(
            "{\"path\":\"" + file + "\",\"content\":\"\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[verified: first line: \"\", last line: \"\"]");
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
        WriteFileTool t = new WriteFileTool(props);
        ToolResult r = t.execute(
            "{\"path\":\"/root/.ssh/key\",\"content\":\"x\"}",
            null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).doesNotContain("[verified:");
    }
}