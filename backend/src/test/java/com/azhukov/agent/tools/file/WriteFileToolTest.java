package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WriteFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonContent(String content) {
        return content.replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }

    private static JsonNode jsonContent(ToolResult result) throws Exception {
        return JSON.readTree(result.content());
    }

    private static void assertVerified(ToolResult result) throws Exception {
        assertThat(jsonContent(result).path("verified").asBoolean()).isTrue();
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);

        ToolResult r = t.execute("{", null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        JsonNode json = jsonContent(r);
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(r.error()).isEqualTo(json.path("error").asText());
    }

    @Test
    void writesFile(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("sub/a.txt");
        ToolResult r = t.execute("{\"path\":\"" + jsonPath(file) + "\",\"content\":\"hello\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
        assertVerified(r);
        assertThat(Files.readString(file)).isEqualTo("hello");
    }

    @Test
    void writesRelativePathInsideSessionWorkdirLikeHermes(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Session session = Session.create("u", "p", "m").withMetadata(TerminalTool.META_WORKDIR, dir.toString());

        ToolResult r = t.execute("{\"path\":\"sub/a.txt\",\"content\":\"hello\"}", null, session);

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(dir.resolve("sub/a.txt"))).isEqualTo("hello");
    }

    @Test
    void writesTildePathInsideUserHomeLikeHermes(@TempDir Path dir) throws Exception {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", dir.toString());
            AgentProperties p = new AgentProperties();
            p.getSecurity().setFileSafetyEnabled(false);
            WriteFileTool t = new WriteFileTool(p);

            ToolResult r = t.execute("{\"path\":\"~/sub/a.txt\",\"content\":\"hello\"}", null, Session.create("u","p","m"));

            assertThat(r.success()).isTrue();
            assertThat(Files.readString(dir.resolve("sub/a.txt"))).isEqualTo("hello");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void invalidJsonIsRefusedBeforeCreatingFile(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("broken.json");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("{\"a\": 1,") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".json syntax validation");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void invalidJsonDoesNotOverwriteExistingFile(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"ok\": true}\n", StandardCharsets.UTF_8);

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("{\"ok\": false,") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".json syntax validation");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{\"ok\": true}\n");
    }

    @Test
    void validJsonWritesWithVerifiedFlag(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("valid.json");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("{\"a\": 1}\n") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertVerified(r);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{\"a\": 1}\n");
    }

    @Test
    void invalidYamlIsRefusedBeforeCreatingFile(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("broken.yaml");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("items: [one, two\n") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".yaml syntax validation");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void customTaggedYamlWritesLikeHermes(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("template.yml");
        String content = "BucketName: !Sub '${AWS::StackName}-bucket'\n";

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent(content) + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertVerified(r);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void invalidTomlIsRefusedBeforeCreatingFileLikeHermes(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("broken.toml");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("[project\nname = \"x\"\n") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".toml syntax validation");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void validTomlWritesWithVerifiedFlag(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("pyproject.toml");
        String content = "[project]\nname = \"java-agent\"\nversion = \"0.1.0\"\n";

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent(content) + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertVerified(r);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void invalidPythonIsNotHardRefused(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("draft.py");
        String content = "def broken(:\n";

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent(content) + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    void requiresPath() {
        WriteFileTool t = new WriteFileTool(new AgentProperties());
        ToolResult r = t.execute("{\"content\":\"hello\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void blocksReadFileLineNumberedDisplayContent(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("copied.txt");
        String display = "1|first line\n2|second line\n3|third line";

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent(display) + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to write internal read_file display text");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void allowsSparseLiteralPipeContent(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("literal.txt");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"1|value\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("1|value");
    }

    @Test
    void blocksReadFileDedupStatusText(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("status.txt");
        String status = "File unchanged since last read. The content from "
            + "the earlier read_file result in this conversation is still current — refer to that instead of re-reading.";

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent(status) + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to write internal read_file display text");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void blocksForbiddenPath() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        ToolResult r = t.execute("{\"path\":\"/root/.ssh/key\",\"content\":\"x\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void respectsAllowedPaths(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(java.util.List.of(dir.toString()));
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("a.txt");
        ToolResult r = t.execute("{\"path\":\"" + jsonPath(file) + "\",\"content\":\"ok\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isTrue();
    }

    @Test
    void deniesPathOutsideAllowed(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(java.util.List.of(dir.toString()));
        WriteFileTool t = new WriteFileTool(p);
        ToolResult r = t.execute("{\"path\":\"/tmp/outside.txt\",\"content\":\"x\"}", null, Session.create("u","p","m"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void blocksNestedEnvWriteEvenWhenAllowedPathMatches(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(true);
        p.getSecurity().setAllowedPaths(java.util.List.of(dir.toString()));
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("sub/.env");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"TOKEN=secret\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void blocksPlainTextWriteToOpaqueDocument(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("report.docx");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"plain text\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to write plain text to binary document");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void blocksPlainTextOverwriteOfExistingPdf(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("manual.pdf");
        Files.writeString(file, "%PDF-1.7\n");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"plain text\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to overwrite existing PDF");
        assertThat(Files.readString(file)).isEqualTo("%PDF-1.7\n");
    }

    @Test
    void allowsPlainTextCreationOfNewPdfLikeHermes(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("new.pdf");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"%PDF-1.7\\n\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("%PDF-1.7\n");
    }

    @Test
    void blocksHermesStateWritesEvenWhenBroadFileSafetyDisabled() {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        String hermesRoot = System.getProperty("user.home", "/root") + "/.hermes";
        Path file = Path.of(hermesRoot, "state.db");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"tamper\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
    }

    @Test
    void preservesExistingCrLfLineEndings(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("windows.txt");
        Files.writeString(file, "old\r\nvalue\r\n", StandardCharsets.UTF_8);

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("new\nvalue\n") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        assertVerified(r);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("new\r\nvalue\r\n");
    }

    @Test
    void preservesExistingUtf8Bom(@TempDir Path dir) throws Exception {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("bom.txt");
        Files.write(file, "\uFEFFold\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"" + jsonContent("new\n") + "\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isTrue();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("\uFEFFnew\n");
    }

    @Test
    void blocksProtectedInstructionFilesEvenWhenBroadFileSafetyDisabled(@TempDir Path dir) {
        AgentProperties p = new AgentProperties();
        p.getSecurity().setFileSafetyEnabled(false);
        WriteFileTool t = new WriteFileTool(p);
        Path file = dir.resolve("AGENTS.md");

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"content\":\"rules\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void blocksCrossProfileSymlinkTarget(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            !System.getProperty("os.name").toLowerCase().contains("win"),
            "Symlink creation is privilege-dependent on Windows");

        Path target = dir.resolve("target.txt");
        Files.writeString(target, "old");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.abort("Cannot create symlink: " + e.getMessage());
        }
        WriteFileTool t = new WriteFileTool(new TargetOnlyCrossProfileSafety(target));

        ToolResult r = t.execute(
            "{\"path\":\"" + jsonPath(link) + "\",\"content\":\"new\"}",
            null, Session.create("u","p","m"));

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("cross-profile target");
        assertThat(Files.readString(target)).isEqualTo("old");
    }

    private record TargetOnlyCrossProfileSafety(Path target) implements FileSafety {
        @Override
        public boolean isPathAllowed(Path path) {
            return true;
        }

        @Override
        public boolean isCommandAllowed(String command) {
            return true;
        }

        @Override
        public Optional<String> getCrossProfileWarning(Path path) {
            return path.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())
                ? Optional.of("cross-profile target")
                : Optional.empty();
        }
    }
}
