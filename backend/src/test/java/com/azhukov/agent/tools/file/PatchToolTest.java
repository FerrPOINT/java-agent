package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PatchToolTest {

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    @Test
    void replaceModePatchesFirstOccurrence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar foo");
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"baz\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("baz bar foo");
    }

    @Test
    void replaceAllModeReplacesAll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo foo");
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"baz\",\"replace_all\":true}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("baz baz");
    }

    @Test
    void failsWhenOldStringNotFound(@TempDir Path dir) {
        Path file = dir.resolve("f.txt");
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"baz\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void failsWhenPathMissing() {
        ToolResult r = tool.execute("{\"old_string\":\"foo\",\"new_string\":\"baz\"}", null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void blocksSensitivePath() {
        ToolResult r = tool.execute("{\"path\":\"/.env\",\"old_string\":\"x\",\"new_string\":\"y\"}", null, session);
        assertThat(r.error()).contains("not allowed");
    }

    @Test
    void v4aAddCreatesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("added.txt");
        String patch = "*** Add File: " + file + "\n+hello";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).contains("hello");
    }

    @Test
    void v4aUpdateExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world");
        String patch = "*** Update File: " + file + "\n-hello\n+hi";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hi world");
    }

    @Test
    void v4aDeleteExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "x");
        String patch = "*** Delete File: " + file;
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void v4aBlockedPath(@TempDir Path dir) {
        String patch = "*** Add File: /root/.ssh/key\n+x";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void v4aUpdateMissingFile(@TempDir Path dir) {
        Path file = dir.resolve("missing.txt");
        String patch = "*** Update File: " + file + "\n-hello\n+hi";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
    }
}
