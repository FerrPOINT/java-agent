package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PatchTool UX improvements:
 * - p7: already-applied no-op (new_string already present → success with info)
 * - p11: multi-match detection (old_string appears more than once → error)
 */
class PatchToolUxTest {

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    // ── p7: Already-applied no-op ──────────────────────────────────────

    @Test
    void alreadyAppliedPatchReturnsSuccess(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "hello world");
        // old_string not in file, but new_string IS → already applied
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"goodbye\",\"new_string\":\"hello world\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[info: new_string already present, no changes needed]");
    }

    @Test
    void alreadyAppliedDoesNotModifyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        String original = "hello world";
        Files.writeString(file, original);
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"xyz\",\"new_string\":\"hello world\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void notAlreadyAppliedWhenOldStringStillPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "hello world hello");
        // old_string "hello" is present, new_string "hi" is not → normal patch
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"hello\",\"new_string\":\"hi\"}",
            null, session);
        // p11 blocks this because "hello" appears twice
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("matches 2 times");
    }

    @Test
    void alreadyAppliedWithSingleOccurrenceOldStringNotPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "replacement text here");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"original\",\"new_string\":\"replacement text\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("[info: new_string already present");
    }

    @Test
    void alreadyAppliedDoesNotTriggerWhenOldStringAlsoPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "old and new both here");
        // Both old_string and new_string present — should NOT trigger already-applied
        // Should proceed to multi-match check or normal replace
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"old\",\"new_string\":\"new\"}",
            null, session);
        // "old" appears once, so it should be a normal patch
        assertThat(r.success()).isTrue();
        assertThat(r.content()).contains("Patched");
    }

    // ── p11: Multi-match detection ─────────────────────────────────────

    @Test
    void multiMatchReturnsError(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "dup dup dup");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"dup\",\"new_string\":\"x\"}",
            null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("[error: old_string matches 3 times");
        assertThat(r.error()).contains("Use replace_all=true");
    }

    @Test
    void multiMatchDoesNotModifyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        String original = "dup dup dup";
        Files.writeString(file, original);
        tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"dup\",\"new_string\":\"x\"}",
            null, session);
        assertThat(Files.readString(file)).isEqualTo(original);
    }

    @Test
    void multiMatchWithReplaceAllStillWorks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "dup dup dup");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"dup\",\"new_string\":\"x\",\"replace_all\":true}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("x x x");
    }

    @Test
    void singleMatchSucceedsNormally(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar baz");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"bar\",\"new_string\":\"qux\"}",
            null, session);
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("foo qux baz");
    }

    @Test
    void multiMatchWithOverlappingSubstrings(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "aa aa");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"aa\",\"new_string\":\"bb\"}",
            null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("matches 2 times");
    }

    @Test
    void multiMatchCountIsAccurate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "target target target target target");
        ToolResult r = tool.execute(
            "{\"path\":\"" + file + "\",\"old_string\":\"target\",\"new_string\":\"done\"}",
            null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("matches 5 times");
    }
}