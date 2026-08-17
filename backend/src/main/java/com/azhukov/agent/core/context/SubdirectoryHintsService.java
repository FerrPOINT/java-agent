package com.azhukov.agent.core.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Feature 5: Subdirectory hints — discovers AGENTS.md/CLAUDE.md/.cursorrules
 * in directories the agent visits via tool calls.
 *
 * Mirrors Hermes agent/subdirectory_hints.py — SubdirectoryHintTracker.
 * Non-intrusive: only appends to tool output, doesn't modify system prompt.
 * Only loads hints from directories within the working directory tree.
 */
@Slf4j
@Component
public class SubdirectoryHintsService {

    private static final List<String> HINT_FILENAMES = List.of(
        "AGENTS.md", "agents.md",
        "CLAUDE.md", "claude.md",
        ".cursorrules"
    );

    private static final int MAX_HINT_CHARS = 8_000;
    private static final int MAX_ANCESTOR_WALK = 5;

    private final Path workingDir;
    private final Set<Path> loadedDirs;

    public SubdirectoryHintsService() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public SubdirectoryHintsService(Path workingDir) {
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.loadedDirs = new LinkedHashSet<>();
        this.loadedDirs.add(this.workingDir);
    }

    /**
     * Check tool call arguments for new directories and load any hint files.
     *
     * @param toolName the tool name
     * @param toolArgs the tool arguments (map with keys like "path", "workdir", "command")
     * @return formatted hint text to append to the tool result, or null if no hints found
     */
    public String checkToolCall(String toolName, java.util.Map<String, Object> toolArgs) {
        Set<Path> dirs = extractDirectories(toolName, toolArgs);
        if (dirs.isEmpty()) return null;

        List<String> allHints = new ArrayList<>();
        for (Path dir : dirs) {
            String hints = loadHintsForDirectory(dir);
            if (hints != null) {
                allHints.add(hints);
            }
        }

        if (allHints.isEmpty()) return null;
        return "\n\n" + String.join("\n\n", allHints);
    }

    private Set<Path> extractDirectories(String toolName, java.util.Map<String, Object> args) {
        Set<Path> candidates = new LinkedHashSet<>();

        // Direct path arguments
        Object pathVal = args.get("path");
        if (pathVal instanceof String s && !s.isBlank()) {
            addPathCandidate(s, candidates);
        }

        Object workdirVal = args.get("workdir");
        if (workdirVal instanceof String s && !s.isBlank()) {
            addPathCandidate(s, candidates);
        }

        // Shell commands — extract path-like tokens
        if ("terminal".equals(toolName)) {
            Object cmdVal = args.get("command");
            if (cmdVal instanceof String cmd) {
                extractPathsFromCommand(cmd, candidates);
            }
        }

        return candidates;
    }

    private void addPathCandidate(String rawPath, Set<Path> candidates) {
        try {
            Path p = Path.of(rawPath);
            if (!p.isAbsolute()) {
                p = workingDir.resolve(p);
            }
            p = p.toAbsolutePath().normalize();
            // Use parent if it's a file path (has extension or exists as file)
            if (p.toString().contains(".") || (Files.exists(p) && Files.isRegularFile(p))) {
                p = p.getParent();
            }
            // Walk up ancestors — stop at already-loaded or root
            for (int i = 0; i < MAX_ANCESTOR_WALK; i++) {
                if (loadedDirs.contains(p)) break;
                if (isValidSubdir(p)) {
                    candidates.add(p);
                }
                Path parent = p.getParent();
                if (parent == null || parent.equals(p)) break;
                p = parent;
            }
        } catch (Exception e) {
            // Path resolution errors — skip silently
        }
    }

    private void extractPathsFromCommand(String cmd, Set<Path> candidates) {
        String[] tokens = cmd.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith("-")) continue;
            if (!token.contains("/") && !token.contains(".")) continue;
            if (token.startsWith("http://") || token.startsWith("https://") || token.startsWith("git@"))
                continue;
            addPathCandidate(token, candidates);
        }
    }

    private boolean isValidSubdir(Path path) {
        try {
            if (!Files.isDirectory(path)) return false;
        } catch (Exception e) {
            return false;
        }
        if (loadedDirs.contains(path)) return false;
        // Only allow subdirectories within the working directory tree
        return path.startsWith(workingDir);
    }

    private String loadHintsForDirectory(Path directory) {
        loadedDirs.add(directory);

        // Reject paths outside the working directory tree
        if (!directory.startsWith(workingDir)) {
            log.debug("Skipping hint files in {} — outside working_dir {}", directory, workingDir);
            return null;
        }

        for (String filename : HINT_FILENAMES) {
            Path hintPath = directory.resolve(filename);
            try {
                if (!Files.isRegularFile(hintPath)) continue;
                String content = Files.readString(hintPath).strip();
                if (content.isEmpty()) continue;
                if (content.length() > MAX_HINT_CHARS) {
                    content = content.substring(0, MAX_HINT_CHARS)
                        + "\n\n[...truncated " + filename + ": " + content.length() + " chars total]";
                }
                // Compute relative path for display
                String relPath;
                try {
                    relPath = workingDir.relativize(hintPath).toString();
                } catch (Exception e) {
                    relPath = hintPath.toString();
                }
                log.debug("Loaded subdirectory hints from {}: {}", directory, filename);
                return "[Context: " + filename + " found in " + relPath + "]\n" + content;
            } catch (IOException e) {
                log.debug("Could not read {}: {}", hintPath, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Get the working directory for this tracker.
     */
    public Path getWorkingDir() {
        return workingDir;
    }

    /**
     * Get the set of already-loaded directories.
     */
    public Set<Path> getLoadedDirs() {
        return Set.copyOf(loadedDirs);
    }
}