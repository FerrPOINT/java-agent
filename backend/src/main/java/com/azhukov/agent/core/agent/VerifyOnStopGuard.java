package com.azhukov.agent.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

/**
 * Hermes parity (agent/verification_stop.py):
 * Turn-end verification guard for coding edits. When the model tries to
 * finish immediately after editing code without fresh verification evidence,
 * a nudge is injected requesting the model run tests/build/lint.
 *
 * Policy-only — never runs checks itself. Decides whether to nudge based on:
 * 1. Files mutated this turn (write_file, patch)
 * 2. Non-code filter (.md, .txt, LICENSE → no nudge)
 * 3. Max 2 nudge attempts per turn
 * 4. Config flag agent.verify-on-stop (default false, opt-in)
 */
@Slf4j
@Component
public class VerifyOnStopGuard {

    private static final int MAX_CHANGED_PATHS_IN_NUDGE = 8;
    private static final int MAX_NUDGE_ATTEMPTS = 2;

    /**
     * Non-code file extensions whose edits carry no verifiable runtime behavior.
     * Hermes _NON_CODE_VERIFY_EXTENSIONS (verification_stop.py:28-42).
     */
    private static final Set<String> NON_CODE_EXTENSIONS = Set.of(
        ".md", ".markdown", ".mdx", ".rst", ".txt", ".text",
        ".adoc", ".asciidoc", ".org", ".log", ".csv", ".tsv"
    );

    /**
     * Filenames (case-insensitive, no extension) that are pure prose.
     * Hermes _NON_CODE_VERIFY_FILENAMES.
     */
    private static final Set<String> NON_CODE_FILENAMES = Set.of(
        "license", "licence", "notice", "authors",
        "contributors", "changelog", "codeowners"
    );

    /**
     * Build a verify-on-stop nudge if the model edited code and hasn't verified.
     *
     * @param changedPaths  file paths mutated this turn
     * @param nudgeAttempts current nudge count (0 = first attempt)
     * @param verifyCommands detected verify commands for the workspace (from CodingWorkspaceSnapshot)
     * @return nudge text, or null if no nudge needed
     */
    public String buildNudge(Set<String> changedPaths, int nudgeAttempts, List<String> verifyCommands) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return null;
        }
        if (nudgeAttempts >= MAX_NUDGE_ATTEMPTS) {
            return null;
        }

        // Filter to verifiable (non-doc) paths
        List<String> verifiable = filterVerifiablePaths(changedPaths);
        if (verifiable.isEmpty()) {
            return null;
        }

        // Build the nudge
        String pathsList = formatChangedPaths(verifiable);
        String commandInstruction;

        if (verifyCommands != null && !verifyCommands.isEmpty()) {
            StringBuilder cmds = new StringBuilder();
            int shown = Math.min(verifyCommands.size(), 3);
            for (int i = 0; i < shown; i++) {
                if (i > 0) cmds.append(", ");
                cmds.append("`").append(verifyCommands.get(i)).append("`");
            }
            if (verifyCommands.size() > 3) cmds.append(", ...");
            commandInstruction = "Run the relevant verification command now (" + cmds + "), "
                + "read any failure, repair the code, and summarize what passed.";
        } else {
            commandInstruction = "No canonical test/lint/build command was detected. "
                + "Create a focused temporary verification script, run it against the "
                + "changed behavior, and summarize it explicitly as ad-hoc verification "
                + "rather than suite green.";
        }

        return "[System: You edited code in this turn, but the workspace does not have "
            + "fresh passing verification evidence yet.\n\n"
            + "Changed paths:\n" + pathsList + "\n\n"
            + commandInstruction + " If verification is not possible, explain the "
            + "concrete blocker instead of claiming the work is fully verified.]";
    }

    /**
     * Filter out non-code paths (documentation, prose, data markup).
     * Hermes _filter_verifiable_paths.
     */
    static List<String> filterVerifiablePaths(Set<String> paths) {
        List<String> result = new ArrayList<>();
        for (String raw : paths) {
            if (raw == null || raw.isBlank()) continue;
            if (!isNonCodePath(raw)) {
                result.add(raw);
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    /**
     * Check if a path is documentation/prose with nothing to verify.
     * Hermes _is_non_code_path.
     */
    static boolean isNonCodePath(String raw) {
        try {
            Path p = Path.of(raw);
            String suffix = p.getFileName().toString();
            int dotIdx = suffix.lastIndexOf('.');
            if (dotIdx >= 0) {
                String ext = suffix.substring(dotIdx).toLowerCase();
                if (NON_CODE_EXTENSIONS.contains(ext)) {
                    return true;
                }
            } else {
                // No extension — check against non-code filenames
                if (NON_CODE_FILENAMES.contains(suffix.toLowerCase())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Format changed paths for the nudge (max 8 shown).
     * Hermes _format_changed_paths.
     */
    private String formatChangedPaths(List<String> paths) {
        int shown = Math.min(paths.size(), MAX_CHANGED_PATHS_IN_NUDGE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            sb.append("- `").append(paths.get(i)).append("`\n");
        }
        int remaining = paths.size() - shown;
        if (remaining > 0) {
            sb.append("- ... and ").append(remaining).append(" more");
        }
        return sb.toString().trim();
    }

    public int getMaxNudgeAttempts() {
        return MAX_NUDGE_ATTEMPTS;
    }
}