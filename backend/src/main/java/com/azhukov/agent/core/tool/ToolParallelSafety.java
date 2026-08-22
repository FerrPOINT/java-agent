package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Determines when a batch of tool calls is safe to run concurrently.
 * <p>
 * Mirrors Hermes' {@code tool_dispatch_helpers._should_parallelize_tool_batch}
 * rules engine. The decision is conservative — when in doubt, run sequentially.
 * <ul>
 *   <li>1 call → sequential (no parallelism benefit).</li>
 *   <li>{@code clarify} in batch → sequential (NEVER_PARALLEL — interactive/user-facing).</li>
 *   <li>Path-scoped tools ({@code read_file}, {@code write_file}, {@code patch})
 *       with overlapping paths → sequential.</li>
 *   <li>Known-safe read-only tools → parallel OK.</li>
 *   <li>Everything else → sequential (default safe).</li>
 * </ul>
 */
@Slf4j
public final class ToolParallelSafety {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tools that must never run concurrently (interactive / user-facing). */
    public static final Set<String> NEVER_PARALLEL_TOOLS = Set.of("clarify");

    /** Read-only tools with no shared mutable session state.
     * Hermes parity (tool_dispatch_helpers.py:48-61) — EXACT set. NOTE:
     * "terminal" was wrongly listed here; terminal is stateful (shared PTY,
     * cwd, env) and Hermes keeps it on the sequential path. */
    public static final Set<String> PARALLEL_SAFE_TOOLS = Set.of(
        "ha_get_state",
        "ha_list_entities",
        "ha_list_services",
        "read_file",
        "search_files",
        "session_search",
        "skill_view",
        "skills_list",
        "vision_analyze",
        "web_extract",
        "web_search"
    );

    /** File tools that can run concurrently when targeting independent paths. */
    public static final Set<String> PATH_SCOPED_TOOLS = Set.of("read_file", "write_file", "patch");

    private ToolParallelSafety() {
    }

    /**
     * Return true when a tool-call batch is safe to run concurrently.
     * <p>
     * Rules (in order of evaluation):
     * <ol>
     *   <li>1 call → sequential (false).</li>
     *   <li>Any call uses a NEVER_PARALLEL tool (e.g. {@code clarify}) → sequential.</li>
     *   <li>Path-scoped tools with overlapping paths → sequential.</li>
     *   <li>All remaining tools must be in PARALLEL_SAFE_TOOLS; if any unknown → sequential.</li>
     * </ol>
     *
     * @param calls               the tool calls in the batch
     * @param registeredToolNames  the set of all registered tool names (for reference; currently unused)
     * @return true if the batch is safe to parallelise, false otherwise
     */
    public static boolean shouldParallelize(List<ToolCall> calls,
                                            Set<String> registeredToolNames) {
        if (calls == null || calls.size() <= 1) {
            return false;
        }

        // Check for never-parallel tools
        for (ToolCall tc : calls) {
            if (NEVER_PARALLEL_TOOLS.contains(tc.name())) {
                return false;
            }
        }

        // Track reserved paths for path-scoped tools
        List<Path> reservedPaths = new ArrayList<>();

        for (ToolCall tc : calls) {
            String toolName = tc.name();

            // Parse arguments
            JsonNode args;
            try {
                args = MAPPER.readTree(tc.arguments());
            } catch (Exception e) {
                log.debug("Could not parse args for {} — defaulting to sequential; raw={}",
                    toolName, tc.arguments() != null && tc.arguments().length() > 200
                        ? tc.arguments().substring(0, 200) : tc.arguments());
                return false;
            }
            if (!args.isObject()) {
                log.debug("Non-dict args for {} ({}) — defaulting to sequential",
                    toolName, args.getNodeType());
                return false;
            }

            // Path-scoped tools: check for overlapping paths
            if (PATH_SCOPED_TOOLS.contains(toolName)) {
                Path scopedPath = extractScopePath(toolName, args);
                if (scopedPath == null) {
                    return false;
                }
                for (Path existing : reservedPaths) {
                    if (pathsOverlap(scopedPath, existing)) {
                        return false;
                    }
                }
                reservedPaths.add(scopedPath);
                continue;
            }

            // Non-path-scoped: must be in parallel-safe set
            if (!PARALLEL_SAFE_TOOLS.contains(toolName)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if two tool calls have overlapping path arguments.
     * <p>
     * Extracts the {@code path} argument from each call and checks for overlap.
     * Returns false if either call doesn't have a path argument or isn't a
     * path-scoped tool.
     *
     * @param a first tool call
     * @param b second tool call
     * @return true if the paths overlap, false otherwise
     */
    public static boolean hasPathOverlap(ToolCall a, ToolCall b) {
        Path pathA = extractPathFromCall(a);
        Path pathB = extractPathFromCall(b);
        if (pathA == null || pathB == null) {
            return false;
        }
        return pathsOverlap(pathA, pathB);
    }

    // ── Path extraction & overlap ──────────────────────────────────────────

    /**
     * Extract the normalised file target for a path-scoped tool call.
     * <p>
     * Mirrors Hermes' {@code _extract_parallel_scope_path}.
     *
     * @param toolName the tool name (must be in PATH_SCOPED_TOOLS)
     * @param args     the parsed JSON arguments
     * @return the normalised path, or null if no valid path found
     */
    static Path extractScopePath(String toolName, JsonNode args) {
        if (!PATH_SCOPED_TOOLS.contains(toolName)) {
            return null;
        }
        return extractPathFromArgs(args);
    }

    private static Path extractPathFromCall(ToolCall tc) {
        if (!PATH_SCOPED_TOOLS.contains(tc.name())) {
            return null;
        }
        try {
            JsonNode args = MAPPER.readTree(tc.arguments());
            return extractPathFromArgs(args);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path extractPathFromArgs(JsonNode args) {
        JsonNode pathNode = args.get("path");
        if (pathNode == null || !pathNode.isTextual()) {
            return null;
        }
        String rawPath = pathNode.asText();
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }

        // Expand ~ (user home)
        String expanded = expandUserHome(rawPath);

        // h69: Canonicalise paths to detect and prevent same-file concurrent mutation.
        // Use Path.normalize() first (removes . and .. without filesystem access),
        // then try Path.toRealPath() for full canonicalisation (resolves symlinks).
        // If toRealPath() fails (file doesn't exist), fall back to normalize().
        Path path = Path.of(expanded);
        if (path.isAbsolute()) {
            return canonicalise(path.toAbsolutePath());
        }

        // Resolve against CWD without calling resolve() to avoid checking existence
        Path cwd = Path.of(System.getProperty("user.dir"));
        return canonicalise(cwd.resolve(path).toAbsolutePath());
    }

    /**
     * h69: Canonicalise a path to its real filesystem path to detect
     * same-file concurrent mutation. Tries {@link Path#toRealPath()} first
     * (resolves symlinks, requires file to exist), falls back to
     * {@link Path#normalize()} for non-existent paths.
     *
     * @param path the absolute path to canonicalise
     * @return the canonicalised path
     */
    static Path canonicalise(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            // File doesn't exist yet or symlink can't be resolved — use normalize
            return path.normalize();
        }
    }

    /**
     * Return true when two paths may refer to the same subtree.
     * <p>
     * Two paths overlap if one is a prefix of the other (component-wise).
     * Mirrors Hermes' {@code _paths_overlap}.
     */
    static boolean pathsOverlap(Path left, Path right) {
        int leftLen = left.getNameCount();
        int rightLen = right.getNameCount();
        if (leftLen == 0 || rightLen == 0) {
            return leftLen == rightLen && leftLen > 0;
        }
        int commonLen = Math.min(leftLen, rightLen);
        for (int i = 0; i < commonLen; i++) {
            if (!left.getName(i).equals(right.getName(i))) {
                return false;
            }
        }
        return true;
    }

    private static String expandUserHome(String path) {
        if (path.startsWith("~/")) {
            String home = System.getProperty("user.home");
            return home + path.substring(1);
        }
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        return path;
    }
}