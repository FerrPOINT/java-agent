package com.azhukov.agent.tools.file;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extra branch-coverage tests for PatchTool.
 */
class PatchToolExtraTest {

    private final PatchTool tool = new PatchTool();
    private final Session session = Session.create("u", "p", "m");

    @Test
    void replaceWithNullOldStringFails(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "hello");
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"mode\":\"replace\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("old_string and new_string are required");
    }

    @Test
    void patchModeWithBlankPatchFails() {
        ToolResult r = tool.execute("{\"mode\":\"patch\",\"patch\":\"\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("patch content is required");
    }

    @Test
    void unknownModeFails(@TempDir Path dir) {
        ToolResult r = tool.execute("{\"mode\":\"foobar\",\"path\":\"" + dir.resolve("x.txt") + "\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Unknown mode");
    }

    @Test
    void replaceOldStringNotInContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "hello world");
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"old_string\":\"worldx\",\"new_string\":\"bye\"}", null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Could not find old_string");
    }

    @Test
    void replaceWhereNewEqualsOld(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "foo bar");
        // old_string == new_string → the string IS found in content, so the patch succeeds.
        // The fix (L32) uses indexOf to check existence rather than comparing updated vs content.
        ToolResult r = tool.execute("{\"path\":\"" + file + "\",\"old_string\":\"foo\",\"new_string\":\"foo\"}", null, session);
        assertThat(r.success()).isTrue();
    }

    @Test
    void v4aDeleteNonExistentFile(@TempDir Path dir) {
        Path file = dir.resolve("nonexistent.txt");
        String patch = "*** Delete File: " + file;
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("File not found for delete");
    }

    @Test
    void v4aUpdateWithNonMatchingDiff(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello world");
        String patch = "*** Update File: " + file + "\n-goodbye\n+bye";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("Could not match old section");
    }

    @Test
    void v4aMixedSuccessAndErrors(@TempDir Path dir) throws Exception {
        Path goodFile = dir.resolve("good.txt");
        // One valid Add + one blocked path
        String patch = "*** Add File: " + goodFile + "\n+hello\n*** Add File: /root/.ssh/key\n+x";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        // V4A operations are preflighted before any write, so a blocked
        // operation must not leave earlier operations partially applied.
        assertThat(r.success()).isFalse();
        assertThat(r.error()).contains("not allowed");
        assertThat(Files.exists(goodFile)).isFalse();
    }

    @Test
    void v4aAddWithContextLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ctx.txt");
        // Context lines start with space
        String patch = "*** Add File: " + file + "\n+hello\n world\n-removed\nplainline";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        assertThat(r.success()).isTrue();
        String content = Files.readString(file);
        assertThat(content).contains("hello");
        assertThat(content).contains("world");
        assertThat(content).contains("plainline");
    }

    @Test
    void v4aAddFailsWithException(@TempDir Path dir) {
        // Try to add to a path that can't be created (parent is a file)
        Path blocking = dir.resolve("blocker");
        try {
            Files.writeString(blocking, "x");
        } catch (Exception ignored) {}
        Path impossible = blocking.resolve("subdir/file.txt");
        String patch = "*** Add File: " + impossible + "\n+hello";
        String json = "{\"mode\":\"patch\",\"patch\":\"" + patch.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        ToolResult r = tool.execute(json, null, session);
        // Should have errors
        assertThat(r.success()).isFalse();
    }
}
