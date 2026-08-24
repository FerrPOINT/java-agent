package com.azhukov.agent.core.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hermes parity (run_agent.py:3569, tool_dispatch_helpers.py:409):
 * Tracks file paths mutated by write_file/patch during a single agent turn.
 * The collected paths feed the verify-on-stop guard: when the model tries to
 * finish immediately after editing code without fresh verification evidence,
 * a nudge is injected requesting the model run tests/build.
 *
 * Per-turn state — cleared at the start of each turn via {@link #resetForTurn}.
 */
@Slf4j
@Component
public class FileMutationTracker {

    private final Set<String> turnMutationPaths = ConcurrentHashMap.newKeySet();
    private int verificationStopNudges = 0;

    /**
     * Reset per-turn state. Called at the top of every agent turn (sync + streaming).
     */
    public void resetForTurn() {
        turnMutationPaths.clear();
        verificationStopNudges = 0;
    }

    /**
     * Record a file mutation from a successful write_file or patch call.
     * Mirrors Hermes _extract_file_mutation_targets + _extract_landed_file_mutation_paths.
     *
     * @param toolName  write_file or patch
     * @param arguments JSON arguments string
     * @param result    tool result content (may contain resolved_path or files_modified)
     * @param success   whether the tool call succeeded
     */
    public void recordMutation(String toolName, String arguments, String result, boolean success) {
        if (!success) {
            return;
        }
        Set<String> targets = extractMutationTargets(toolName, arguments);
        if (targets.isEmpty()) {
            return;
        }
        // Try to extract landed paths from JSON result (resolved_path, files_modified)
        Set<String> landed = extractLandedPaths(result, targets);
        turnMutationPaths.addAll(landed);
        log.debug("Recorded file mutation paths: {} (total this turn: {})", landed, turnMutationPaths.size());
    }

    /**
     * Get the set of file paths mutated in the current turn.
     */
    public Set<String> getTurnMutationPaths() {
        return new HashSet<>(turnMutationPaths);
    }

    /**
     * Increment and get the verification stop nudge count.
     */
    public int incrementVerificationStopNudges() {
        return ++verificationStopNudges;
    }

    public int getVerificationStopNudges() {
        return verificationStopNudges;
    }

    // ── Path extraction (Hermes tool_dispatch_helpers.py:409-500 parity) ──

    /**
     * Extract target file paths from write_file/patch arguments.
     * For write_file: args["path"].
     * For patch replace mode: args["path"].
     * For patch V4A mode: parse *** Update/Add/Delete File: headers.
     */
    static Set<String> extractMutationTargets(String toolName, String arguments) {
        Set<String> paths = new HashSet<>();
        if (arguments == null || arguments.isBlank()) {
            return paths;
        }
        if ("write_file".equals(toolName)) {
            String path = extractJsonField(arguments, "path");
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        } else if ("patch".equals(toolName)) {
            String mode = extractJsonField(arguments, "mode");
            if (mode == null || "replace".equals(mode)) {
                String path = extractJsonField(arguments, "path");
                if (path != null && !path.isBlank()) {
                    paths.add(path);
                }
            } else if ("patch".equals(mode)) {
                String patch = extractJsonField(arguments, "patch");
                if (patch != null && !patch.isBlank()) {
                    // Parse V4A headers: *** Update File: path, *** Add File: path
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "^\\*\\*\\*\\s*(?:Update|Add|Delete)\\s+File:\\s*(.+)$",
                        java.util.regex.Pattern.MULTILINE
                    ).matcher(patch);
                    while (m.find()) {
                        String p = m.group(1).trim();
                        if (!p.isBlank()) paths.add(p);
                    }
                    // Parse *** Move File: src -> dst
                    m = java.util.regex.Pattern.compile(
                        "^\\*\\*\\*\\s*Move\\s+File:\\s*(.+?)\\s*->\\s*(.+)$",
                        java.util.regex.Pattern.MULTILINE
                    ).matcher(patch);
                    while (m.find()) {
                        String src = m.group(1).trim();
                        String dst = m.group(2).trim();
                        if (!src.isBlank()) paths.add(src);
                        if (!dst.isBlank()) paths.add(dst);
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Extract landed file paths from successful tool result JSON.
     * Hermes checks files_modified list, then resolved_path.
     */
    static Set<String> extractLandedPaths(String result, Set<String> fallback) {
        if (result == null || result.isBlank()) {
            return fallback;
        }
        String stripped = result.trim();
        if (!stripped.startsWith("{")) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(stripped);
            // files_modified list
            com.fasterxml.jackson.databind.JsonNode filesNode = node.get("files_modified");
            if (filesNode != null && filesNode.isArray()) {
                Set<String> landed = new HashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode f : filesNode) {
                    String p = f.asText();
                    if (p != null && !p.isBlank()) landed.add(p);
                }
                if (!landed.isEmpty()) return landed;
            }
            // resolved_path
            com.fasterxml.jackson.databind.JsonNode resolved = node.get("resolved_path");
            if (resolved != null && !resolved.isNull()) {
                String p = resolved.asText();
                if (p != null && !p.isBlank()) return Set.of(p);
            }
        } catch (Exception e) {
            // Not JSON, use fallback
        }
        return fallback;
    }

    /**
     * Simple JSON field extractor (avoids full parse for common cases).
     */
    private static String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        // Try Jackson first
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode val = node.get(field);
            if (val != null && !val.isNull()) return val.asText();
        } catch (Exception e) {
            // Fallback: regex
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"([^\"]+)\""
            ).matcher(json);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}