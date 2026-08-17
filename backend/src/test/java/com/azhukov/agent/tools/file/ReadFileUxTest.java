package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ReadFileTool UX improvements:
 * - p10: truncation UX — when output is truncated by limit, show remaining lines count
 * - h55: UTF-16 BOM detection — detect UTF-16 BOM (FF FE or FE FF) and transcode
 */
class ReadFileUxTest {

    private final Session session = Session.create("u", "p", "m");

    private ReadFileTool newTool() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        return new ReadFileTool(props);
    }

    // ── p10: Truncation UX ─────────────────────────────────────────────

    @Test
    void truncationShowsRemainingLines(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lines.txt"), "a\nb\nc\nd\ne\nf\ng\nh\ni\nj");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("lines.txt") + "\",\"offset\":1,\"limit\":3}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[truncated: showing lines 1-3 of 10 total, 7 remaining]");
    }

    @Test
    void truncationWithOffsetShowsCorrectLines(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lines.txt"), "a\nb\nc\nd\ne\nf\ng\nh\ni\nj");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("lines.txt") + "\",\"offset\":5,\"limit\":2}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[truncated: showing lines 5-6 of 10 total, 4 remaining]");
    }

    @Test
    void noTruncationWhenAllLinesShown(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("small.txt"), "a\nb\nc");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("small.txt") + "\",\"offset\":1,\"limit\":100}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).doesNotContain("[truncated:");
    }

    @Test
    void noTruncationWhenLimitExactlyMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("exact.txt"), "a\nb\nc");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("exact.txt") + "\",\"offset\":1,\"limit\":3}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).doesNotContain("[truncated:");
    }

    @Test
    void truncationWithSingleLineRemaining(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("two.txt"), "a\nb");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + dir.resolve("two.txt") + "\",\"offset\":1,\"limit\":1}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[truncated: showing lines 1-1 of 2 total, 1 remaining]");
    }

    // ── h55: UTF-16 BOM detection ──────────────────────────────────────

    @Test
    void readsUtf16LeFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("utf16le.txt");
        // Write UTF-16LE with BOM (FF FE)
        String text = "hello world\nline two";
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] contentBytes = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] full = new byte[bom.length + contentBytes.length];
        System.arraycopy(bom, 0, full, 0, bom.length);
        System.arraycopy(contentBytes, 0, full, bom.length, contentBytes.length);
        Files.write(file, full);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":100}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello world");
        assertThat(r.content()).contains("line two");
    }

    @Test
    void readsUtf16BeFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("utf16be.txt");
        // Write UTF-16BE with BOM (FE FF)
        String text = "hello world\nline two";
        byte[] bom = {(byte) 0xFE, (byte) 0xFF};
        byte[] contentBytes = text.getBytes(StandardCharsets.UTF_16BE);
        byte[] full = new byte[bom.length + contentBytes.length];
        System.arraycopy(bom, 0, full, 0, bom.length);
        System.arraycopy(contentBytes, 0, full, bom.length, contentBytes.length);
        Files.write(file, full);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":100}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello world");
        assertThat(r.content()).contains("line two");
    }

    @Test
    void utf8FileWithoutBomReadsNormally(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("utf8.txt");
        Files.writeString(file, "hello world\nline two");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":100}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("hello world");
        assertThat(r.content()).contains("line two");
    }

    @Test
    void utf16LeFileWithTruncation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("utf16le_trunc.txt");
        String text = "line1\nline2\nline3\nline4\nline5";
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        byte[] contentBytes = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] full = new byte[bom.length + contentBytes.length];
        System.arraycopy(bom, 0, full, 0, bom.length);
        System.arraycopy(contentBytes, 0, full, bom.length, contentBytes.length);
        Files.write(file, full);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":2}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("line1");
        assertThat(r.content()).contains("line2");
        assertThat(r.content()).contains("[truncated: showing lines 1-2 of 5 total, 3 remaining]");
    }

    @Test
    void emptyUtf16File(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty_utf16.txt");
        byte[] bom = {(byte) 0xFF, (byte) 0xFE};
        Files.write(file, bom);

        ReadFileTool tool = newTool();
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":100}",
            null, session);
        assertThat(r.success()).isTrue();
    }
}