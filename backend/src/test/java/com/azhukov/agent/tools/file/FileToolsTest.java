package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolsTest {

    private final AgentProperties properties = new AgentProperties();
    private final WriteFileTool writeTool = new WriteFileTool(properties, fileSafety());
    private final ReadFileTool readTool = new ReadFileTool(properties);

    @Test
    void writeAndReadRoundTrip(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("hello.txt");

        var writeResult = writeTool.execute(
            "{\"path\":\"" + file + "\",\"content\":\"line1\\nline2\\nline3\"}", null, null);
        assertThat(writeResult.success()).isTrue();

        var readResult = readTool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":10}", null, null);
        assertThat(readResult.success()).isTrue();
        assertThat(readResult.content()).contains("1|line1", "2|line2", "3|line3");
    }

    @Test
    void readWithOffsetAndLimit(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("offset.txt");
        Files.writeString(file, "a\nb\nc\nd\n");

        var result = readTool.execute("{\"path\":\"" + file + "\",\"offset\":2,\"limit\":2}", null, null);
        assertThat(result.content()).contains("2|b", "3|c");
        // With limit=2 and more lines remaining, the JSON envelope carries the
        // continuation hint (offset=4) instead of a plain-text marker.
        assertThat(result.content()).contains("\"truncated\":true", "\"hint\":\"Use offset=4");
    }

    @Test
    void failsOnMissingFile() {
        var result = readTool.execute("{\"path\":\"/tmp/does-not-exist-12345.txt\",\"offset\":1,\"limit\":10}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("File not found");
    }

    @Test
    void writeFailsWithoutPath() {
        var result = writeTool.execute("{\"content\":\"x\"}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("path is required");
    }

    @Test
    void writeBlocksSensitivePath() {
        var result = writeTool.execute("{\"path\":\"/etc/passwd\",\"content\":\"x\"}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("denied");
    }

    private static com.azhukov.agent.core.security.DefaultFileSafety fileSafety() {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        props.getSecurity().setFileSafetyEnabled(true);
        return new com.azhukov.agent.core.security.DefaultFileSafety(props);
    }
}
