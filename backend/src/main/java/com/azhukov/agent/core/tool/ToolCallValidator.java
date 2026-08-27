package com.azhukov.agent.core.tool;

import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates and sanitises tool calls before execution.
 * <p>
 * Mirrors Hermes' {@code conversation_loop.py} tool-call validation pipeline:
 * <ul>
 *   <li>{@link #validateToolNames} — check tool names against registered tools,
 *       attempt fuzzy repair (Levenshtein distance ≤ 2), return error results
 *       for unknown tools.</li>
 *   <li>{@link #validateJsonArgs} — parse arguments as JSON, detect truncation
 *       (doesn't end with {@code } } or {@code ] }), return error results for
 *       invalid JSON.</li>
 *   <li>{@link #deduplicateToolCalls} — remove duplicate tool calls (same name +
 *       same arguments).</li>
 *   <li>{@link #capDelegateTaskCalls} — limit {@code delegate_task} calls to
 *       {@code maxDelegateTasks} (default 1) per batch.</li>
 * </ul>
 * All methods are static and stateless.
 */
@Slf4j
public final class ToolCallValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max Levenshtein distance for fuzzy tool-name repair. */
    private static final int MAX_LEVENSHTEIN = 2;

    /** Default cap for delegate_task calls per batch. */
    private static final int DEFAULT_MAX_DELEGATE_TASKS = 1;

    private ToolCallValidator() {
    }

    // ── Tool name validation & repair ──────────────────────────────────────

    /**
     * Ensure every tool call in a single assistant turn has a distinct id
     * (Hermes parity: message_sanitization.uniquify_tool_call_ids, #58327 loss class).
     *
     * <p>Some models/providers reuse one call id across different calls in a single
     * batch (observed with Kimi Responses replays, Ollama-compatible endpoints, and
     * degraded models at long context). Duplicate ids are lossy downstream: strict
     * providers (Anthropic tool_use, DeepSeek) reject duplicate ids outright, and
     * payload sanitizers keep only the first call/result pair per id — the later
     * call's result silently vanishes from replayed payloads.</p>
     *
     * <p>The first occurrence keeps its id; later collisions get a deterministic
     * {@code <id>_d<n>} suffix — never a random UUID, which would break
     * prompt-cache prefix stability across replays. Composite ids
     * ({@code call_x|fc_y}) collide on the call half (the pairing key providers
     * enforce per turn) while the response-item half is preserved on rename.
     * Blank/missing ids are left untouched. Mutates the list in place
     * (replace-by-index) and returns the number of ids rewritten.</p>
     */
    public static int uniquifyToolCallIds(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.size() < 2) {
            return 0;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        int rewritten = 0;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            String cid = tc.pairingId();
            if (cid.isEmpty()) {
                continue; // deterministic fallback path owns blank ids
            }
            if (seen.add(cid)) {
                continue; // first occurrence — keep
            }
            int n = 2;
            String newId = cid + "_d" + n;
            while (seen.contains(newId)) {
                n++;
                newId = cid + "_d" + n;
            }
            seen.add(newId);
            // Preserve a composite id's response-item half (fc_/item id survives).
            toolCalls.set(i, tc.withPairingId(newId));
            rewritten++;
            log.warn("Duplicate tool_call id '{}' in batch — renamed to '{}' (call #{})",
                cid, newId, i + 1);
        }
        return rewritten;
    }

    /**
     * Validate tool names against the set of registered tool names.
     * <p>
     * For each tool call whose name is not in {@code registeredToolNames},
     * attempts fuzzy repair (Levenshtein distance ≤ {@value #MAX_LEVENSHTEIN}).
     * If repair succeeds, the tool call's name is updated in-place.
     * <p>
     * Returns a list of error messages for tool calls that remain unknown after
     * repair. If the list is empty, all tool names are valid.
     *
     * @param toolCalls           the tool calls to validate (mutated in-place for repairs)
     * @param registeredToolNames the set of valid tool names
     * @return list of error messages for unrepairable tool calls; empty if all valid
     */
    public static List<String> validateToolNames(List<ToolCall> toolCalls,
                                                 Set<String> registeredToolNames) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            if (registeredToolNames.contains(tc.name())) {
                continue;
            }
            // Attempt fuzzy repair
            String repaired = repairToolName(tc.name(), registeredToolNames);
            if (repaired != null) {
                log.info("Auto-repaired tool name: '{}' -> '{}'", tc.name(), repaired);
                toolCalls.set(i, new ToolCall(tc.id(), repaired, tc.arguments()));
            } else {
                String preview = tc.name().length() > 80
                    ? tc.name().substring(0, 80) + "..."
                    : tc.name();
                errors.add("Tool '" + preview + "' does not exist. Available tools: "
                    + String.join(", ", sorted(registeredToolNames)));
            }
        }
        return errors;
    }

    /**
     * Attempt to repair a mismatched tool name.
     * <p>
     * Mirrors Hermes' {@code repair_tool_call}: normalises casing/separators,
     * strips {@code _tool} suffixes, then falls back to Levenshtein fuzzy match
     * with distance ≤ {@value #MAX_LEVENSHTEIN}.
     *
     * @param toolName            the raw tool name from the model
     * @param registeredToolNames the set of valid tool names
     * @return the repaired name if found, or {@code null}
     */
    static String repairToolName(String toolName, Set<String> registeredToolNames) {
        if (toolName == null || toolName.isEmpty()) {
            return null;
        }

        // Step 1: trim XML/quote fragments (VolcEngine workaround)
        String cleaned = toolName;
        for (char sep : new char[]{'"', '\'', '<', '>'}) {
            int idx = cleaned.indexOf(sep);
            if (idx > 0) {
                cleaned = cleaned.substring(0, idx);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }

        // Step 2: lowercase direct match
        String lowered = cleaned.toLowerCase();
        if (registeredToolNames.contains(lowered)) {
            return lowered;
        }

        // Step 3: normalise hyphens/spaces to underscores
        String normalized = lowered.replace('-', '_').replace(' ', '_');
        if (registeredToolNames.contains(normalized)) {
            return normalized;
        }

        // Step 4: CamelCase → snake_case
        String camelSnake = camelToSnake(cleaned);
        if (registeredToolNames.contains(camelSnake)) {
            return camelSnake;
        }

        // Step 5: strip trailing _tool / -tool / tool suffix (applied twice)
        Set<String> candidates = new HashSet<>();
        candidates.add(cleaned);
        candidates.add(lowered);
        candidates.add(normalized);
        candidates.add(camelSnake);
        for (int round = 0; round < 2; round++) {
            Set<String> extra = new HashSet<>();
            for (String c : candidates) {
                String stripped = stripToolSuffix(c);
                if (stripped != null) {
                    extra.add(stripped);
                    extra.add(stripped.toLowerCase().replace('-', '_').replace(' ', '_'));
                    extra.add(camelToSnake(stripped));
                }
            }
            candidates.addAll(extra);
        }
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && registeredToolNames.contains(c)) {
                return c;
            }
        }

        // Step 6: Levenshtein fuzzy match (distance ≤ MAX_LEVENSHTEIN)
        String bestMatch = null;
        int bestDistance = MAX_LEVENSHTEIN + 1;
        for (String registered : registeredToolNames) {
            int dist = levenshtein(lowered, registered.toLowerCase());
            if (dist <= MAX_LEVENSHTEIN && dist < bestDistance) {
                bestDistance = dist;
                bestMatch = registered;
            }
        }
        return bestMatch;
    }

    // ── JSON argument validation ───────────────────────────────────────────

    /**
     * Result of JSON argument validation.
     */
    public record JsonValidationResult(List<String> errors, boolean truncated) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    /**
     * Validate that tool call arguments are parseable JSON.
     * <p>
     * For each tool call, normalises the arguments:
     * <ul>
     *   <li>Dict/list arguments are serialised to JSON strings.</li>
     *   <li>Empty/whitespace strings become {@code "{}"}.</li>
     *   <li>Non-string, non-null arguments are stringified.</li>
     * </ul>
     * Then validates that each argument string parses as JSON. If parsing fails,
     * checks for truncation (arguments that don't end with {@code } } or
     * {@code ] } after stripping whitespace).
     *
     * @param toolCalls the tool calls to validate (arguments are normalised in-place)
     * @return validation result with errors and truncation flag
     */
    public static JsonValidationResult validateJsonArgs(List<ToolCall> toolCalls) {
        List<String> errors = new ArrayList<>();
        boolean anyTruncated = false;

        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall tc = toolCalls.get(i);
            String args = tc.arguments();

            // Normalise: null → "{}" (ToolCall record requires non-null, but callers
            // may pass null strings through other paths)
            if (args == null || args.strip().isEmpty()) {
                toolCalls.set(i, new ToolCall(tc.id(), tc.name(), "{}"));
                continue;
            }

            // Validate JSON
            try {
                MAPPER.readTree(args);
            } catch (Exception e) {
                // Check for truncation: args that don't end with } or ]
                String stripped = args.strip();
                if (!stripped.endsWith("}") && !stripped.endsWith("]")) {
                    anyTruncated = true;
                }
                errors.add("Invalid JSON in tool call arguments for '" + tc.name()
                    + "': " + e.getMessage());
            }
        }

        return new JsonValidationResult(errors, anyTruncated);
    }

    // ── Deduplication ──────────────────────────────────────────────────────

    /**
     * Remove duplicate tool calls (same name + same arguments) within a batch.
     * <p>
     * Only the first occurrence of each unique pair is kept. Mirrors Hermes'
     * {@code _deduplicate_tool_calls}.
     *
     * @param toolCalls the tool calls to deduplicate
     * @return deduplicated list (may be the same list if no duplicates)
     */
    public static List<ToolCall> deduplicateToolCalls(List<ToolCall> toolCalls) {
        Set<String> seen = new HashSet<>();
        List<ToolCall> unique = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            String key = tc.name() + "\0" + tc.arguments();
            if (seen.add(key)) {
                unique.add(tc);
            } else {
                log.warn("Removed duplicate tool call: {}", tc.name());
            }
        }
        return unique.size() < toolCalls.size() ? unique : toolCalls;
    }

    // ── Delegate task capping ──────────────────────────────────────────────

    /**
     * Limit {@code delegate_task} calls to {@code maxDelegateTasks} per batch.
     * <p>
     * Preserves all non-delegate calls. Mirrors Hermes'
     * {@code _cap_delegate_task_calls} (which uses max_concurrent_children; here
     * we default to 1 per the task spec).
     *
     * @param toolCalls the tool calls to cap
     * @return capped list (may be the same list if no capping needed)
     */
    public static List<ToolCall> capDelegateTaskCalls(List<ToolCall> toolCalls) {
        return capDelegateTaskCalls(toolCalls, DEFAULT_MAX_DELEGATE_TASKS);
    }

    /**
     * Limit {@code delegate_task} calls to {@code maxDelegateTasks} per batch.
     *
     * @param toolCalls          the tool calls to cap
     * @param maxDelegateTasks   maximum number of delegate_task calls allowed
     * @return capped list
     */
    public static List<ToolCall> capDelegateTaskCalls(List<ToolCall> toolCalls,
                                                     int maxDelegateTasks) {
        long delegateCount = toolCalls.stream()
            .filter(tc -> "delegate_task".equals(tc.name()))
            .count();
        if (delegateCount <= maxDelegateTasks) {
            return toolCalls;
        }
        List<ToolCall> truncated = new ArrayList<>();
        int keptDelegates = 0;
        for (ToolCall tc : toolCalls) {
            if ("delegate_task".equals(tc.name())) {
                if (keptDelegates < maxDelegateTasks) {
                    truncated.add(tc);
                    keptDelegates++;
                }
            } else {
                truncated.add(tc);
            }
        }
        log.warn("Truncated {} excess delegate_task call(s) to enforce max={}",
            delegateCount - maxDelegateTasks, maxDelegateTasks);
        return truncated;
    }

    // ── Helper: Levenshtein distance ───────────────────────────────────────

    static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;

        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    // ── Helper: name normalisation ─────────────────────────────────────────

    private static String camelToSnake(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String stripToolSuffix(String s) {
        if (s == null) return null;
        String lc = s.toLowerCase();
        for (String suffix : new String[]{"_tool", "-tool", "tool"}) {
            if (lc.endsWith(suffix) && s.length() > suffix.length()) {
                String stripped = s.substring(0, s.length() - suffix.length());
                // Strip trailing _ or -
                while (stripped.endsWith("_") || stripped.endsWith("-")) {
                    stripped = stripped.substring(0, stripped.length() - 1);
                }
                return stripped;
            }
        }
        return null;
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        java.util.Collections.sort(list);
        return list;
    }
}