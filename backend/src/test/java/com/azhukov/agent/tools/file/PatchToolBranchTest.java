package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage tests for {@link PatchTool}.
 * Covers fuzzy matching strategies, blocked paths, mode validation, and V4A format.
 */
class PatchToolBranchTest {

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    // ── Mode validation ──

    @Test
    void unknownMode_returnsFail() {
        ToolResult r = tool.execute(
            "{\"path\":\"/tmp/test.txt\",\"mode\":\"invalid\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Unknown mode");
    }

    @Test
    void replaceMode_missingOldString_returnsFail() {
        ToolResult r = tool.execute(
            "{\"path\":\"/tmp/test.txt\",\"mode\":\"replace\",\"new_string\":\"x\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("old_string and new_string are required");
    }

    @Test
    void replaceMode_missingNewString_returnsFail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "content");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"mode\":\"replace\",\"old_string\":\"content\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("old_string and new_string are required");
    }

    @Test
    void replaceMode_fileNotFound_returnsFail() {
        ToolResult r = tool.execute(
            "{\"path\":\"/tmp/nonexistent_patch_test.txt\",\"mode\":\"replace\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found");
    }

    @Test
    void patchMode_missingPatchContent_returnsFail() {
        ToolResult r = tool.execute(
            "{\"mode\":\"patch\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("patch content is required");
    }

    @Test
    void patchMode_blankPatchContent_returnsFail() {
        ToolResult r = tool.execute(
            "{\"mode\":\"patch\",\"patch\":\"  \"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("patch content is required");
    }

    @Test
    void noPath_noPatchMode_returnsFail() {
        ToolResult r = tool.execute(
            "{\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("path is required");
    }

    @Test
    void nullMode_defaultsToReplace() {
        ToolResult r = tool.execute(
            "{\"path\":\"/tmp/nonexistent.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found");
    }

    // ── Blocked paths ──

    @Test
    void replaceMode_blockedPath_envFile() {
        ToolResult r = tool.execute(
            "{\"path\":\"/.env\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not allowed");
    }

    @Test
    void replaceMode_blockedPath_etcShadow() {
        ToolResult r = tool.execute(
            "{\"path\":\"/etc/shadow\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not allowed");
    }

    @Test
    void replaceMode_blockedPath_rootSsh() {
        ToolResult r = tool.execute(
            "{\"path\":\"/root/.ssh/id_rsa\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not allowed");
    }

    @Test
    void replaceMode_blockedPath_etcPasswd() {
        ToolResult r = tool.execute(
            "{\"path\":\"/etc/passwd\",\"old_string\":\"a\",\"new_string\":\"b\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not allowed");
    }

    // ── Fuzzy matching strategies ──

    @Test
    void replaceMode_trimmedWhitespaceFuzzy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "  hello world  ");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello world\",\"new_string\":\"goodbye\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void replaceMode_caseInsensitiveFuzzy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "Hello World");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello world\",\"new_string\":\"goodbye world\"}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).contains("goodbye world");
    }

    @Test
    void replaceMode_bomStrippedFuzzy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "hello world".getBytes();
        byte[] full = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, full, 0, bom.length);
        System.arraycopy(content, 0, full, bom.length, content.length);
        Files.write(file, full);
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello world\",\"new_string\":\"goodbye world\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void replaceMode_allStrategiesFail_returnsFail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "completely different content");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"nonexistent text\",\"new_string\":\"replacement\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Could not find old_string");
        assertThat(r.error()).contains("fuzzy strategies");
    }

    @Test
    void replaceMode_normalizedLineEndingsFuzzy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "hello\r\nworld\r\n");
        // old_string has LF, file has CRLF → strategy 2 normalizes
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello\\nworld\",\"new_string\":\"goodbye\\nworld\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    // ── V4A patch format ──

    @Test
    void v4a_addFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("new_file.txt");
        String patch = "*** Add File: " + target + "\n+line1\n+line2\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.readString(target)).contains("line1");
    }

    @Test
    void v4a_deleteFile_notFound_reportsError(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nonexistent.txt");
        String patch = "*** Delete File: " + target + "\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        // When only errors and no modifications, returns fail
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not found");
    }

    @Test
    void v4a_updateFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("update_me.txt");
        Files.writeString(target, "old content\nkeep this line\n");
        String patch = "*** Update File: " + target + "\n-old content\n+new content\n keep this line\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(target)).contains("new content");
    }

    @Test
    void v4a_updateFile_notFound_reportsError(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("missing.txt");
        String patch = "*** Update File: " + target + "\n-old\n+new\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isNotNull();
    }

    @Test
    void v4a_addFile_blockedPath_returnsError() {
        String patch = "*** Add File: /.env\n+secret\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        // Should have errors about blocked path — since all operations failed, returns fail
        assertThat(r.success()).isFalse();
    }

    @Test
    void v4a_multipleOperations(@TempDir Path dir) throws Exception {
        Path addFile = dir.resolve("add.txt");
        Path updateFile = dir.resolve("update.txt");
        Files.writeString(updateFile, "old\n");
        String patch = "*** Add File: " + addFile + "\n+new content\n*** Update File: " + updateFile + "\n-old\n+new\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.exists(addFile)).isTrue();
        assertThat(Files.readString(updateFile)).contains("new");
    }

    @Test
    void v4a_updateFile_diffMismatch_reportsError(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("update.txt");
        Files.writeString(target, "content\n");
        String patch = "*** Update File: " + target + "\n-nonexistent\n+new\n*** End Patch";
        String json = "{\"mode\":\"patch\",\"patch\":" + toJson(patch) + "}";
        ToolResult r = tool.execute(json, null, session);
        // The mismatch causes an error in the update, but it's reported in the result
        // Since there are errors but also potentially empty modifications, check both
        if (r.success()) {
            assertThat(r.content()).contains("Failed to update");
        } else {
            assertThat(r.error()).isNotNull();
        }
    }

    // ── replace_all ──

    @Test
    void replaceMode_replaceAll_replacesAllOccurrences(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar foo baz foo");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"x\",\"replace_all\":true}", null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("x bar x baz x");
    }

    // ── replace where old equals new (string found, no change) ──

    @Test
    void replaceMode_replaceAllNoChange_returnsSuccess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"foo\",\"replace_all\":true}", null, session);
        // When old_string is found in content, the patch succeeds even if new_string equals old_string.
        // The fix (L32) uses indexOf to check existence rather than comparing updated vs content.
        assertThat(r.success()).isTrue();
    }

    /** Convert a raw string to a JSON string literal */
    private static String toJson(String s) {
        return quoteJson(s);
    }

    private static String quoteJson(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}