package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;



class PatchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonPatch(String patch) {
        return "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
    }

    private static String jsonContent(String content) {
        return content.replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        ToolResult r = tool.execute("{", null, session);

        assertThat(r.success()).isFalse();
        JsonNode json = JSON.readTree(r.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(r.error()).isEqualTo(json.path("error").asText());
    }

    @Test
    void replaceModePatchesFirstOccurrence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar baz");
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"foo\",\"new_string\":\"baz\"}", null, session);
        assertThat(r.success()).isTrue();
        JsonNode json = JSON.readTree(r.content());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.path("files_modified").get(0).asText()).isEqualTo(file.toString());
        assertThat(json.path("diff").asText()).contains("-foo bar baz", "+baz bar baz");
        assertThat(Files.readString(file)).isEqualTo("baz bar baz");
    }

    @Test
    void replaceModeResolvesRelativePathFromSessionWorkdirLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar");
        Session cwdSession = session.withMetadata(TerminalTool.META_WORKDIR, dir.toString());

        ToolResult r = tool.execute(
            "{\"path\":\"f.txt\",\"old_string\":\"foo\",\"new_string\":\"baz\"}",
            null,
            cwdSession);

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("baz bar");
    }

    @Test
    void replaceModeRefusesInvalidJsonAndLeavesOriginal(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"ok\": true}\n", StandardCharsets.UTF_8);

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\""
                + jsonContent("{\"ok\": true}\n") + "\",\"new_string\":\""
                + jsonContent("{\"ok\": false,") + "\"}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".json syntax validation");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{\"ok\": true}\n");
    }

    @Test
    void replaceAllModeReplacesAll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo foo");
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"foo\",\"new_string\":\"baz\",\"replace_all\":true}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("baz baz");
    }

    @Test
    void failsWhenOldStringNotFound(@TempDir Path dir) {
        Path file = dir.resolve("f.txt");
        ToolResult r = tool.execute("{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"foo\",\"new_string\":\"baz\"}", null, session);
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
    void replaceModeBlocksNestedEnv(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "TOKEN=old");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"old\",\"new_string\":\"new\"}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
        assertThat(Files.readString(file)).isEqualTo("TOKEN=old");
    }

    @Test
    void replaceModeBlocksOpaqueDocument(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("report.docx");
        Files.writeString(file, "old");

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"old\",\"new_string\":\"new\"}",
            null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to write plain text to binary document");
        assertThat(Files.readString(file)).isEqualTo("old");
    }

    @Test
    void v4aAddBlocksNestedEnv(@TempDir Path dir) {
        Path file = dir.resolve("sub/.env");
        String patch = "*** Add File: " + file + "\n+TOKEN=secret";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";

        ToolResult r = tool.execute(json, null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void v4aAddRejectsTraversalHeaderBeforeNormalize(@TempDir Path dir) {
        String rawPath = dir.resolve("nested") + "/../created.txt";

        ToolResult r = tool.execute(jsonPatch("*** Add File: " + rawPath + "\n+created"), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("V4A patch header contains '..' traversal");
        assertThat(Files.exists(dir.resolve("created.txt"))).isFalse();
    }

    @Test
    void v4aAddBlocksOpaqueDocument(@TempDir Path dir) {
        Path file = dir.resolve("report.xlsx");

        ToolResult r = tool.execute(jsonPatch("*** Add File: " + file + "\n+plain text"), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to write plain text to binary document");
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void v4aAddCreatesFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("added.txt");
        String patch = "*** Add File: " + file + "\n+hello";
        String json = jsonPatch(patch);
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).contains("hello");
    }

    @Test
    void v4aPreflightRefusesInvalidJsonBeforeApplyingEarlierAdd(@TempDir Path dir) {
        Path added = dir.resolve("added.txt");
        Path json = dir.resolve("broken.json");
        String patch = "*** Add File: " + added + "\n+created\n"
            + "*** Add File: " + json + "\n+{\"ok\": false,";

        ToolResult r = tool.execute(jsonPatch(patch), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains(".json syntax validation");
        assertThat(Files.exists(added)).isFalse();
        assertThat(Files.exists(json)).isFalse();
    }

    @Test
    void v4aUpdateExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world");
        String patch = "*** Update File: " + file + "\n-hello\n+hi";
        String json = jsonPatch(patch);
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hi world");
    }

    @Test
    void v4aPatchHeadersResolveFromSessionWorkdirLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world");
        Session cwdSession = session.withMetadata(TerminalTool.META_WORKDIR, dir.toString());

        ToolResult r = tool.execute(jsonPatch("*** Update File: a.txt\n-hello\n+hi"), null, cwdSession);

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("hi world");
    }

    @Test
    void v4aUpdateAppliesMultipleHunksInOneFileLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("multi.txt");
        Files.writeString(file, "alpha\nold one\nmiddle\nold two\nomega");
        String patch = "*** Begin Patch\n"
            + "*** Update File: " + file + "\n"
            + "@@ first @@\n"
            + " alpha\n"
            + "-old one\n"
            + "+new one\n"
            + "@@ second @@\n"
            + " middle\n"
            + "-old two\n"
            + "+new two\n"
            + "*** End Patch";

        ToolResult r = tool.execute(jsonPatch(patch), null, session);

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("alpha\nnew one\nmiddle\nnew two\nomega");
    }

    @Test
    void v4aDeleteExistingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "x");
        String patch = "*** Delete File: " + file;
        String json = jsonPatch(patch);
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
        String json = jsonPatch(patch);
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
    }

    @Test
    void replaceModePreservesExistingCrLfLineEndings(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("windows.txt");
        Files.writeString(file, "hello\r\nworld\r\n", StandardCharsets.UTF_8);

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"world\",\"new_string\":\"earth\\nmoon\"}",
            null, session);

        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("hello\r\nearth\r\nmoon\r\n");
    }

    @Test
    void replaceModePreservesExistingUtf8Bom(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bom.txt");
        Files.write(file, "\uFEFFhello\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = tool.execute(
            "{\"path\":\"" + jsonPath(file) + "\",\"old_string\":\"hello\",\"new_string\":\"hi\"}",
            null, session);

        assertThat(r.success()).isTrue();
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("\uFEFFhi\n");
    }

    @Test
    void v4aMoveRenamesFile(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("old.txt");
        Path destination = dir.resolve("nested/new.txt");
        Files.writeString(source, "payload");

        ToolResult r = tool.execute(jsonPatch("*** Move File: " + source + " -> " + destination), null, session);

        assertThat(r.success()).isTrue();
        assertThat(Files.exists(source)).isFalse();
        assertThat(Files.readString(destination)).isEqualTo("payload");
    }

    @Test
    void v4aMoveRejectsTraversalDestinationBeforeNormalize(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("old.txt");
        Files.writeString(source, "payload");
        String rawDestination = dir.resolve("nested") + "/../new.txt";

        ToolResult r = tool.execute(jsonPatch("*** Move File: " + source + " -> " + rawDestination), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("V4A patch header contains '..' traversal");
        assertThat(Files.exists(source)).isTrue();
        assertThat(Files.exists(dir.resolve("new.txt"))).isFalse();
    }

    @Test
    void v4aDeleteAllowsOpaqueDocumentLikeHermes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("old.docx");
        Files.writeString(file, "payload");

        ToolResult r = tool.execute(jsonPatch("*** Delete File: " + file), null, session);

        assertThat(r.success()).isTrue();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void v4aUpdateBlocksExistingPdfBeforeApplyingOtherChanges(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("manual.pdf");
        Path added = dir.resolve("added.txt");
        Files.writeString(pdf, "%PDF-1.7\n");
        String patch = "*** Update File: " + pdf + "\n-%PDF-1.7\n+plain text\n"
            + "*** Add File: " + added + "\n+created";

        ToolResult r = tool.execute(jsonPatch(patch), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Refusing to overwrite existing PDF");
        assertThat(Files.readString(pdf)).isEqualTo("%PDF-1.7\n");
        assertThat(Files.exists(added)).isFalse();
    }

    @Test
    void v4aPatchPreflightsMissingUpdateBeforeApplyingAdd(@TempDir Path dir) {
        Path added = dir.resolve("added.txt");
        Path missing = dir.resolve("missing.txt");
        String patch = "*** Add File: " + added + "\n+created\n"
            + "*** Update File: " + missing + "\n-old\n+new";

        ToolResult r = tool.execute(jsonPatch(patch), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found for update");
        assertThat(Files.exists(added)).isFalse();
    }

    @Test
    void v4aAddBlocksProtectedInstructionFile(@TempDir Path dir) {
        Path file = dir.resolve("AGENTS.md");

        ToolResult r = tool.execute(jsonPatch("*** Add File: " + file + "\n+rules"), null, session);

        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Access denied");
        assertThat(Files.exists(file)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void m13BlockedPathTraversalBypassIsClosed() {
        // M13 regression: isBlocked used to run on the RAW string before
        // normalize — "/x/../.env" and "/./.env" slipped through.
        var t = new PatchTool();
        String raw = java.nio.file.Path.of("/tmp", "x", "..", "..", "root", ".env").toString()
            .replace(java.nio.file.Path.of("/tmp").toString().replace("tmp", ""), "/");
        // Direct normalized form of /.env via traversal
        String traversal = "/etc/../root/.ssh/authorized_keys";
        var r1 = t.execute("{\"mode\":\"replace\",\"path\":\"" + traversal
            + "\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, null);
        assertThat(r1.success()).isFalse();
        var r2 = t.execute("{\"mode\":\"replace\",\"path\":\"/x/../.env\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, null);
        assertThat(r2.success()).isFalse();
    }
}
