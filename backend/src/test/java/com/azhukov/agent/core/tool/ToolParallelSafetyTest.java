package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolParallelSafety}.
 * <p>
 * Mirrors Hermes' {@code _should_parallelize_tool_batch} rules:
 * <ul>
 *   <li>1 call → sequential</li>
 *   <li>{@code clarify} in batch → sequential (NEVER_PARALLEL)</li>
 *   <li>read_file/write_file/patch with overlapping paths → sequential</li>
 *   <li>Known-safe tools → parallel OK</li>
 *   <li>Everything else → sequential (default safe)</li>
 * </ul>
 */
class ToolParallelSafetyTest {

    private static final Set<String> REGISTERED = Set.of(
        "read_file", "write_file", "patch", "terminal", "search_files",
        "clarify", "skill_view", "session_search", "web_search",
        "web_extract", "vision_analyze", "skills_list"
    );

    // ── shouldParallelize ─────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldParallelize")
    class ShouldParallelize {

        @Test
        @DisplayName("Single call → sequential (false)")
        void singleCallSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("Empty list → sequential (false)")
        void emptyListSequential() {
            assertThat(ToolParallelSafety.shouldParallelize(List.of(), REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("Null list → sequential (false)")
        void nullListSequential() {
            assertThat(ToolParallelSafety.shouldParallelize(null, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("Two known-safe tools → parallel (true)")
        void twoKnownSafeToolsParallel() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "search_files", "{\"pattern\":\"foo\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("Three known-safe tools → parallel (true)")
        void threeKnownSafeToolsParallel() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "search_files", "{\"pattern\":\"foo\"}"),
                new ToolCall("c3", "web_search", "{\"query\":\"bar\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        // ── NEVER_PARALLEL tools ──

        @Test
        @DisplayName("clarify in batch → sequential")
        void clarifyForcesSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "clarify", "{\"question\":\"are you sure?\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("clarify alone with another parallel-safe tool → sequential")
        void clarifyAloneForcesSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "clarify", "{}"),
                new ToolCall("c2", "search_files", "{}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── Path-scoped tools ──

        @Test
        @DisplayName("read_file with non-overlapping paths → parallel")
        void readFileNonOverlappingPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/b.txt\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("read_file with overlapping paths → sequential")
        void readFileOverlappingPaths() {
            // One path is a prefix of the other
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/sub\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("read_file with identical paths → sequential (overlap)")
        void readFileIdenticalPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a.txt\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("write_file with overlapping paths → sequential")
        void writeFileOverlappingPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"x\"}"),
                new ToolCall("c2", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"y\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("patch with overlapping paths → sequential")
        void patchOverlappingPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "patch", "{\"path\":\"/tmp/a.txt\",\"old\":\"x\",\"new\":\"y\"}"),
                new ToolCall("c2", "patch", "{\"path\":\"/tmp/a.txt\",\"old\":\"a\",\"new\":\"b\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("read_file + write_file with same path → sequential")
        void readWriteSamePath() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}"),
                new ToolCall("c2", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"x\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("read_file + write_file with different paths → parallel")
        void readWriteDifferentPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}"),
                new ToolCall("c2", "write_file", "{\"path\":\"/tmp/b.txt\",\"content\":\"x\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("Path-scoped tool with no path arg → sequential")
        void pathScopedNoPathArg() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "read_file", "{\"path\":\"/tmp/b.txt\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── Unknown/unregistered tools ──

        @Test
        @DisplayName("Unknown tool in batch → sequential")
        void unknownToolForcesSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "unknown_tool", "{}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── Invalid JSON args ──

        @Test
        @DisplayName("Invalid JSON args → sequential")
        void invalidJsonArgsForcesSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{invalid json"),
                new ToolCall("c2", "search_files", "{\"pattern\":\"foo\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── terminal (parallel-safe) ──

        @Test
        @DisplayName("Two terminal calls → parallel (terminal is parallel-safe)")
        void twoTerminalCallsParallel() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "terminal", "{\"command\":\"ls\"}"),
                new ToolCall("c2", "terminal", "{\"command\":\"pwd\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        // ── Mixed safe + unsafe ──

        @Test
        @DisplayName("Mixed safe + unsafe tools → sequential")
        void mixedSafeUnsafe() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a\"}"),
                new ToolCall("c2", "write_file", "{\"path\":\"/tmp/b\",\"content\":\"x\"}"),
                new ToolCall("c3", "unknown_tool", "{}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        @Test
        @DisplayName("Non-dict args (array) → sequential")
        void nonDictArgsSequential() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "[1, 2, 3]"),
                new ToolCall("c2", "search_files", "{}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── Relative paths ──

        @Test
        @DisplayName("Relative paths resolved against CWD, non-overlapping → parallel")
        void relativePathsNonOverlapping() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"src/a.java\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"src/b.java\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("Relative path overlapping with absolute path → sequential")
        void relativeOverlappingAbsolute() {
            // This depends on CWD, but if both are the same relative path, they overlap
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"src/a.java\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"src/a.java\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }

        // ── User home expansion ──

        @Test
        @DisplayName("Tilde-expanded paths, non-overlapping → parallel")
        void tildeExpandedPathsNonOverlapping() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"~/a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"~/b.txt\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isTrue();
        }

        @Test
        @DisplayName("Tilde-expanded identical paths → sequential")
        void tildeExpandedIdenticalPaths() {
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"~/a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"~/a.txt\"}")
            );
            assertThat(ToolParallelSafety.shouldParallelize(calls, REGISTERED)).isFalse();
        }
    }

    // ── hasPathOverlap ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("hasPathOverlap")
    class HasPathOverlap {

        @Test
        @DisplayName("Identical paths → overlap")
        void identicalPathsOverlap() {
            ToolCall a = new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isTrue();
        }

        @Test
        @DisplayName("Prefix path → overlap")
        void prefixPathOverlap() {
            ToolCall a = new ToolCall("c1", "read_file", "{\"path\":\"/tmp\"}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/tmp/sub\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isTrue();
        }

        @Test
        @DisplayName("Non-overlapping paths → no overlap")
        void nonOverlappingNoOverlap() {
            ToolCall a = new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/var/b.txt\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isFalse();
        }

        @Test
        @DisplayName("Non-path-scoped tools → no overlap (returns false)")
        void nonPathScopedToolsNoOverlap() {
            ToolCall a = new ToolCall("c1", "terminal", "{\"command\":\"ls\"}");
            ToolCall b = new ToolCall("c2", "search_files", "{\"pattern\":\"foo\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isFalse();
        }

        @Test
        @DisplayName("One path-scoped, one not → no overlap")
        void oneScopedOneNot() {
            ToolCall a = new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            ToolCall b = new ToolCall("c2", "terminal", "{\"command\":\"ls\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isFalse();
        }

        @Test
        @DisplayName("Missing path arg → no overlap")
        void missingPathArg() {
            ToolCall a = new ToolCall("c1", "read_file", "{}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isFalse();
        }

        @Test
        @DisplayName("Siblings (same parent, different file) → no overlap")
        void siblingsNoOverlap() {
            ToolCall a = new ToolCall("c1", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/tmp/b.txt\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isFalse();
        }

        @Test
        @DisplayName("write_file overlapping with read_file → overlap")
        void writeReadOverlap() {
            ToolCall a = new ToolCall("c1", "write_file", "{\"path\":\"/tmp/a.txt\",\"content\":\"x\"}");
            ToolCall b = new ToolCall("c2", "read_file", "{\"path\":\"/tmp/a.txt\"}");
            assertThat(ToolParallelSafety.hasPathOverlap(a, b)).isTrue();
        }
    }

    // ── pathsOverlap (direct path tests) ───────────────────────────────────

    @Nested
    @DisplayName("pathsOverlap")
    class PathsOverlapTest {

        @Test
        @DisplayName("Identical absolute paths → overlap")
        void identicalAbsolutePaths() {
            java.nio.file.Path a = java.nio.file.Path.of("/tmp/a.txt").toAbsolutePath();
            java.nio.file.Path b = java.nio.file.Path.of("/tmp/a.txt").toAbsolutePath();
            assertThat(ToolParallelSafety.pathsOverlap(a, b)).isTrue();
        }

        @Test
        @DisplayName("Prefix → overlap")
        void prefixOverlap() {
            java.nio.file.Path a = java.nio.file.Path.of("/tmp").toAbsolutePath();
            java.nio.file.Path b = java.nio.file.Path.of("/tmp/sub").toAbsolutePath();
            assertThat(ToolParallelSafety.pathsOverlap(a, b)).isTrue();
        }

        @Test
        @DisplayName("Different branches → no overlap")
        void differentBranchesNoOverlap() {
            java.nio.file.Path a = java.nio.file.Path.of("/tmp/a.txt").toAbsolutePath();
            java.nio.file.Path b = java.nio.file.Path.of("/var/b.txt").toAbsolutePath();
            assertThat(ToolParallelSafety.pathsOverlap(a, b)).isFalse();
        }
    }
}