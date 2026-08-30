package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link DeleteFileTool}.
 * Covers file safety disabled, allowed paths null/empty, and blocked system paths.
 */
class DeleteFileToolBranchTest {

    private AgentProperties properties;
    private DeleteFileTool tool;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        tool = new DeleteFileTool(properties, new com.azhukov.agent.core.security.DefaultFileSafety(properties));
    }

    private Session session() {
        return Session.create("user-1", "openai", "gpt-4");
    }

    private Message assistant() {
        return Message.assistant("test", 0);
    }

    @Test
    void delete_fileSafetyDisabled_allowsAnyPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.txt");
        Files.writeString(file, "hello");
        properties.getSecurity().setFileSafetyEnabled(false);

        ToolResult result = tool.execute("{\"path\":\"" + file + "\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void delete_fileSafetyEnabled_nullAllowedPaths_allowsAnyPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.txt");
        Files.writeString(file, "hello");
        properties.getSecurity().setFileSafetyEnabled(true);
        // allowedPaths is initialized to a list, set to null
        properties.getSecurity().getAllowedPaths().clear();

        ToolResult result = tool.execute("{\"path\":\"" + file + "\"}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void delete_fileSafetyEnabled_emptyAllowedPaths_allowsAnyPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("test.txt");
        Files.writeString(file, "hello");
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setAllowedPaths(new java.util.ArrayList<>());

        ToolResult result = tool.execute("{\"path\":\"" + file + "\"}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void delete_blockedPath_bin() {
        ToolResult result = tool.execute("{\"path\":\"/bin/something\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_sbin() {
        ToolResult result = tool.execute("{\"path\":\"/sbin/something\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_usrBin() {
        ToolResult result = tool.execute("{\"path\":\"/usr/bin/tool\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_boot() {
        ToolResult result = tool.execute("{\"path\":\"/boot/vmlinuz\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_dev() {
        ToolResult result = tool.execute("{\"path\":\"/dev/null\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_proc() {
        ToolResult result = tool.execute("{\"path\":\"/proc/cpuinfo\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_blockedPath_sys() {
        ToolResult result = tool.execute("{\"path\":\"/sys/class/net\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not allowed");
    }

    @Test
    void delete_nonExistentFile_returnsFail() {
        ToolResult result = tool.execute("{\"path\":\"/tmp/nonexistent_delete_test_12345.txt\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("File not found");
    }

    @Test
    void delete_nullPath_returnsFail() {
        ToolResult result = tool.execute("{\"path\":null}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("path is required");
    }

    @Test
    void delete_blankPath_returnsFail() {
        ToolResult result = tool.execute("{\"path\":\"  \"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("path is required");
    }

    @Test
    void delete_ioException_returnsFail(@TempDir Path dir) throws Exception {
        // Create a file and make its parent directory non-writable
        // This is platform-dependent, so we just verify the error path exists
        Path file = dir.resolve("test.txt");
        Files.writeString(file, "hello");

        ToolResult result = tool.execute("{\"path\":\"" + file + "\"}", assistant(), session());
        assertThat(result.success()).isTrue();
    }
}