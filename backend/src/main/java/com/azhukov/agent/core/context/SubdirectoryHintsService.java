package com.azhukov.agent.core.context;

import com.azhukov.agent.core.agent.SessionDeletedEvent;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.SecureFileAccess;
import com.azhukov.agent.service.ProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<Scope, Set<Path>> scopedLoadedDirs = new ConcurrentHashMap<>();

    @Autowired
    private ProfileService profileService;

    private record Scope(UUID sessionId, String profile) {}

    @EventListener
    public void removeSessionHints(SessionDeletedEvent event) {
        scopedLoadedDirs.keySet().removeIf(scope -> scope.sessionId().equals(event.sessionId()));
    }

    public SubdirectoryHintsService() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public SubdirectoryHintsService(Path workingDir) {
        this.workingDir = workingDir.toAbsolutePath().normalize();
        this.loadedDirs = ConcurrentHashMap.newKeySet();
        this.loadedDirs.add(this.workingDir);
    }

    /**
     * Check tool call arguments for new directories and load any hint files.
     *
     * @param toolName the tool name
     * @param toolArgs the tool arguments (map with keys like "path", "workdir", "command")
     * @return formatted hint text to append to the tool result, or null if no hints found
     */
    public String checkToolCall(String toolName, Map<String, Object> toolArgs, Session session) {
        if (session == null || "default".equals(session.profile())) {
            return checkToolCall(toolName, toolArgs);
        }
        Path workspace = profileService.workspacePath(session.profile());
        var scope = new Scope(session.id(), session.profile());
        Set<Path> visited = scopedLoadedDirs.computeIfAbsent(scope, key -> ConcurrentHashMap.newKeySet());
        visited.add(workspace);
        return checkToolCall(toolName, toolArgs, workspace, visited);
    }

    public String checkToolCall(String toolName, Map<String, Object> toolArgs) {
        return checkToolCall(toolName, toolArgs, workingDir, loadedDirs);
    }

    private String checkToolCall(String toolName, Map<String, Object> toolArgs, Path root, Set<Path> visited) {
        Set<Path> dirs = extractDirectories(toolName, toolArgs, root, visited);
        if (dirs.isEmpty()) return null;

        List<String> allHints = new ArrayList<>();
        for (Path dir : dirs) {
            String hints = loadHintsForDirectory(dir, root, visited);
            if (hints != null) {
                allHints.add(hints);
            }
        }

        if (allHints.isEmpty()) return null;
        return "\n\n" + String.join("\n\n", allHints);
    }

    private Set<Path> extractDirectories(String toolName, Map<String, Object> args, Path root, Set<Path> visited) {
        Set<Path> candidates = new LinkedHashSet<>();

        // Direct path arguments
        Object pathVal = args.get("path");
        if (pathVal instanceof String s && !s.isBlank()) {
            addPathCandidate(s, candidates, root, visited);
        }

        Object workdirVal = args.get("workdir");
        if (workdirVal instanceof String s && !s.isBlank()) {
            addPathCandidate(s, candidates, root, visited);
        }

        // Shell commands — extract path-like tokens
        if ("terminal".equals(toolName)) {
            Object cmdVal = args.get("command");
            if (cmdVal instanceof String cmd) {
                extractPathsFromCommand(cmd, candidates, root, visited);
            }
        }

        return candidates;
    }

    private void addPathCandidate(String rawPath, Set<Path> candidates, Path root, Set<Path> visited) {
        try {
            Path p = Path.of(rawPath);
            if (!p.isAbsolute()) {
                p = root.resolve(p);
            }
            p = p.toAbsolutePath().normalize();
            if (!p.startsWith(root) || p.equals(root)) {
                return;
            }
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                || (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().contains("."))) {
                p = p.getParent();
            }
            for (int i = 0; i < MAX_ANCESTOR_WALK && p != null && p.startsWith(root); i++) {
                if (visited.contains(p)) break;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    candidates.add(p);
                }
                p = p.getParent();
            }
        } catch (Exception e) {
            // Path resolution errors — skip silently
        }
    }

    private void extractPathsFromCommand(String cmd, Set<Path> candidates, Path root, Set<Path> visited) {
        String[] tokens = cmd.split("\\s+");
        for (String token : tokens) {
            if (token.startsWith("-")) continue;
            if (!token.contains("/") && !token.contains(".")) continue;
            if (token.startsWith("http://") || token.startsWith("https://") || token.startsWith("git@"))
                continue;
            addPathCandidate(token, candidates, root, visited);
        }
    }

    private String loadHintsForDirectory(Path directory, Path root, Set<Path> visited) {
        if (!directory.startsWith(root) || !visited.add(directory)) {
            return null;
        }

        for (String filename : HINT_FILENAMES) {
            Path hintPath = directory.resolve(filename);
            try {
                String content = SecureFileAccess.readUtf8(hintPath).strip();
                if (content.isEmpty()) continue;
                if (content.length() > MAX_HINT_CHARS) {
                    content = content.substring(0, MAX_HINT_CHARS)
                        + "\n\n[...truncated " + filename + ": " + content.length() + " chars total]";
                }
                // Compute relative path for display
                String relPath;
                try {
                    relPath = root.relativize(hintPath).toString();
                } catch (Exception e) {
                    relPath = hintPath.toString();
                }
                log.debug("Loaded subdirectory hints from {}: {}", directory, filename);
                return "[Context: " + filename + " found in " + relPath + "]\n" + content;
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException e) {
                log.debug("Could not safely read context file {}", hintPath);
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