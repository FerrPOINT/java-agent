package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentProperties properties = new AgentProperties();
    private final WriteFileTool writeTool = new WriteFileTool(properties);
    private final ReadFileTool readTool = new ReadFileTool(properties);

    private JsonNode payload(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    private JsonNode errorPayload(ToolResult result) throws Exception {
        JsonNode json = payload(result);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).isEqualTo(result.error());
        return json;
    }

    @Test
    void writeAndReadRoundTrip(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("hello.txt");

        var writeResult = writeTool.execute(
            "{\"path\":\"" + file + "\",\"content\":\"line1\\nline2\\nline3\"}", null, null);
        assertThat(writeResult.success()).isTrue();

        var readResult = readTool.execute(
            "{\"path\":\"" + file + "\",\"offset\":1,\"limit\":10}", null, null);
        assertThat(readResult.success()).isTrue();
        assertThat(payload(readResult).path("content").asText()).contains("1|line1", "2|line2", "3|line3");
    }

    @Test
    void readWithOffsetAndLimit(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("offset.txt");
        Files.writeString(file, "a\nb\nc\nd\n");

        var result = readTool.execute("{\"path\":\"" + file + "\",\"offset\":2,\"limit\":2}", null, null);
        JsonNode json = payload(result);
        assertThat(json.path("content").asText()).contains("2|b", "3|c");
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("hint").asText()).contains("offset=4");
    }

    @Test
    void failsOnMissingFile() throws Exception {
        var result = readTool.execute("{\"path\":\"/tmp/does-not-exist-12345.txt\",\"offset\":1,\"limit\":10}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).path("error").asText()).contains("File not found");
    }

    @Test
    void writeFailsWithoutPath() throws Exception {
        var result = writeTool.execute("{\"content\":\"x\"}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).path("error").asText()).contains("path is required");
    }

    @Test
    void writeBlocksSensitivePath() throws Exception {
        var result = writeTool.execute("{\"path\":\"/etc/passwd\",\"content\":\"x\"}", null, null);
        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).path("error").asText()).contains("not allowed");
    }
}
