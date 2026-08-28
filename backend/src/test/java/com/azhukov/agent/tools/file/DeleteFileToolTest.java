package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeleteFileToolTest {

    private DeleteFileTool tool;
    private AgentProperties properties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        tool = new DeleteFileTool(properties, fileSafety());
    }

    private Session dummySession() {
        return Session.create("test-user", "test-provider", "test-model");
    }

    private Message dummyMessage() {
        return Message.assistant("test", 0);
    }

    @Test
    @DisplayName("Should delete existing file")
    void shouldDeleteExistingFile() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        String args = "{\"path\":\"" + file + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertTrue(result.content().contains("Deleted file"));
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("Should fail when file not found")
    void shouldFailWhenFileNotFound() {
        String args = "{\"path\":\"" + tempDir.resolve("nonexistent.txt") + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("File not found"));
    }

    @Test
    @DisplayName("Should fail when path is null")
    void shouldFailWhenPathIsNull() {
        String args = "{\"path\":null}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("path is required"));
    }

    @Test
    @DisplayName("Should fail when path is blank")
    void shouldFailWhenPathIsBlank() {
        String args = "{\"path\":\"\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("path is required"));
    }

    @Test
    @DisplayName("Should refuse to delete directory")
    void shouldRefuseToDeleteDirectory() {
        String args = "{\"path\":\"" + tempDir + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Refusing to delete directory"));
    }

    @Test
    @DisplayName("Should block deletion of sensitive paths")
    void shouldBlockSensitivePaths() {
        String args = "{\"path\":\"/etc/passwd\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("not allowed"));
    }

    @Test
    @DisplayName("Should block deletion of /.env")
    void shouldBlockEnvFile() {
        String args = "{\"path\":\"/.env\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("not allowed"));
    }

    @Test
    @DisplayName("Should block deletion of /root/.ssh")
    void shouldBlockSshDirectory() {
        String args = "{\"path\":\"/root/.ssh/some_key\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("not allowed"));
    }

    @Test
    @DisplayName("Should enforce allowed-paths when file safety enabled")
    void shouldEnforceAllowedPaths() throws Exception {
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());

        Path file = tempDir.resolve("allowed.txt");
        Files.writeString(file, "test");

        String args = "{\"path\":\"" + file + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertTrue(result.success());
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("Should deny path outside allowed paths when safety enabled")
    void shouldDenyPathOutsideAllowed() throws Exception {
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());

        Path outside = Path.of("/tmp/outside_delete_test.txt");
        if (Files.exists(outside)) {
            Files.delete(outside);
        }
        Files.writeString(outside, "test");

        String args = "{\"path\":\"" + outside + "\"}";
        ToolResult result = tool.execute(args, dummyMessage(), dummySession());

        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("Access denied"));
        // Cleanup
        Files.deleteIfExists(outside);
    }

    private static com.azhukov.agent.core.security.DefaultFileSafety fileSafety() {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        props.getSecurity().setFileSafetyEnabled(true);
        return new com.azhukov.agent.core.security.DefaultFileSafety(props);
    }
}
