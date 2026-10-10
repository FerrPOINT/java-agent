package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies TOML validation at the file-tool boundary before any disk mutation. */
class TomlFileValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID_TOML = """
        [project]
        name = "café"
        [project.build]
        targets = ["jvm", "native"]
        [[contributors]]
        name = "Ada"
        """;

    @TempDir
    Path dir;

    private final Session session = Session.create("u", "p", "m");
    private WriteFileTool writeTool;
    private PatchTool patchTool;

    @BeforeEach
    void configureTools() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setAllowedPaths(List.of(dir.toString()));
        DefaultFileSafety safety = new DefaultFileSafety(properties);
        writeTool = new WriteFileTool(properties, safety);
        patchTool = new PatchTool(safety);
    }

    @Test
    void writeValidNestedAndArrayTomlPersistsExactUtf8Bytes() throws Exception {
        Path file = dir.resolve("nested/project.toml");

        ToolResult result = writeTool.execute(JSON.writeValueAsString(Map.of(
            "path", file.toString(), "content", VALID_TOML)), null, session);

        assertThat(result.success()).isTrue();
        assertThat(JSON.readTree(result.content()).path("verified").asBoolean()).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(VALID_TOML.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name = \"a\"\nname = \"b\"\n", "targets = [1,\n", "name = \"unterminated\n"})
    void writeInvalidTomlCannotCreateFileOrParent(String invalidToml) throws Exception {
        Path file = dir.resolve("new-parent/config.toml");

        ToolResult result = writeTool.execute(JSON.writeValueAsString(Map.of(
            "path", file.toString(), "content", invalidToml)), null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains(".toml syntax validation");
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(file.getParent())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name = \"a\"\nname = \"b\"\n", "targets = [1,\n", "name = \"unterminated\n"})
    void writeInvalidTomlPreservesExistingBytes(String invalidToml) throws Exception {
        Path file = dir.resolve("existing.toml");
        byte[] original = VALID_TOML.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);

        ToolResult result = writeTool.execute(JSON.writeValueAsString(Map.of(
            "path", file.toString(), "content", invalidToml)), null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains(".toml syntax validation");
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    void patchValidNestedAndArrayTomlPersistsExactUtf8Bytes() throws Exception {
        Path file = dir.resolve("project.toml");
        Files.writeString(file, VALID_TOML, StandardCharsets.UTF_8);
        String updated = VALID_TOML.replace("[\"jvm\", \"native\"]", "[\"jvm\", \"native\", \"wasm\"]");

        ToolResult result = patchTool.execute(JSON.writeValueAsString(Map.of(
            "path", file.toString(), "old_string", "[\"jvm\", \"native\"]",
            "new_string", "[\"jvm\", \"native\", \"wasm\"]")), null, session);

        assertThat(result.success()).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(updated.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void v4aPreflightInvalidTomlUpdatePreservesOriginalAndSkipsEarlierAdd() throws Exception {
        Path added = dir.resolve("added.txt");
        Path config = dir.resolve("config.toml");
        byte[] original = "targets = [1, 2]\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, original);
        String patch = "*** Add File: " + added + "\n+created\n"
            + "*** Update File: " + config + "\n-targets = [1, 2]\n+targets = [1,";

        ToolResult result = patchTool.execute(JSON.writeValueAsString(Map.of(
            "mode", "patch", "patch", patch)), null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains(".toml syntax validation");
        assertThat(Files.exists(added)).isFalse();
        assertThat(Files.readAllBytes(config)).isEqualTo(original);
    }
}
