package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    private final Session session = Session.create("u", "p", "m");

    private ReadFileTool newTool() {
        AgentProperties props = new AgentProperties();
        props.getSecurity().setFileSafetyEnabled(false);
        return new ReadFileTool(props);
    }

    // ── Normal file reading ────────────────────────────────────────────

    @Test
    void readsNormalTextFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hello.txt"), "line1\nline2\nline3");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("hello.txt") + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("1|line1");
        assertThat(r.content()).contains("2|line2");
        assertThat(r.content()).contains("3|line3");
    }

    @Test
    void readsFileWithOffsetAndLimit(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lines.txt"), "a\nb\nc\nd\ne");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("lines.txt") + "\",\"offset\":2,\"limit\":2}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("2|b");
        assertThat(r.content()).contains("3|c");
        assertThat(r.content()).doesNotContain("a");
        assertThat(r.content()).doesNotContain("4|d");
    }

    // ── Binary file detection ──────────────────────────────────────────

    @Test
    void rejectsBinaryFileByExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("image.png"), "fake png content");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("image.png") + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Binary file detected");
        assertThat(r.error()).contains(".png");
    }

    @Test
    void rejectsAnotherBinaryExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("archive.zip"), "fake zip");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("archive.zip") + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Binary file detected");
    }

    @Test
    void rejectsClassFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Main.class"), "fake class");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("Main.class") + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Binary file detected");
    }

    @Test
    void binaryDetectionIsCaseInsensitive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("photo.JPG"), "fake jpg");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("photo.JPG") + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Binary file detected");
    }

    // ── Device path blocking ──────────────────────────────────────────

    @Test
    void blocksDevZero() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/zero\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
        assertThat(r.error()).contains("/dev/zero");
    }

    @Test
    void blocksDevRandom() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/random\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevUrandom() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/urandom\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    @Test
    void blocksDevNull() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/dev/null\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Device file blocked");
    }

    // ── Char cap truncation ────────────────────────────────────────────

    @Test
    void truncatesLargeFileAtCharCap(@TempDir Path dir) throws Exception {
        // Each line is ~20 chars ("12345|padding...\n"). Need > 100000 chars total.
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            content.append("padding-padding-padding-line-").append(i).append("\n");
        }
        Files.writeString(dir.resolve("big.txt"), content.toString());
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("big.txt") + "\",\"offset\":1,\"limit\":10000}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content().length()).isGreaterThan(100_000);
        assertThat(r.content()).contains("[... file truncated at 100000 chars]");
        // The truncation marker should be near the end
        int markerIndex = r.content().indexOf("[... file truncated at 100000 chars]");
        assertThat(markerIndex).isGreaterThan(99_000);
    }

    @Test
    void doesNotTruncateSmallFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("small.txt"), "hello world\nshort file");
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir.resolve("small.txt") + "\",\"offset\":1,\"limit\":100}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).doesNotContain("[... file truncated");
    }

    // ── Nonexistent / not-a-file ───────────────────────────────────────

    @Test
    void fileNotFoundReturnsError() {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"/tmp/nonexistent_file_12345.txt\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found");
    }

    @Test
    void directoryReturnsNotAFileError(@TempDir Path dir) {
        ReadFileTool tool = newTool();
        ToolResult r = tool.execute("{\"path\":\"" + dir + "\",\"offset\":1,\"limit\":10}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Not a file");
    }
}