package com.azhukov.agent.core.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hermes parity for agent/coding_context.py runtime posture resolution.
 *
 * Decides whether a session should use the coding posture, separately from
 * language/framework detection. A bare git repository is coding only when it
 * actually contains source files: notes repositories must remain general.
 */
@Slf4j
@Component
public class CodingPostureResolver {

    private static final Set<String> INTERACTIVE_CODING_SURFACES = Set.of("cli", "tui", "acp", "desktop", "");
    private static final Set<String> PROJECT_MARKERS = Set.of(
        "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt",
        "package.json", "tsconfig.json", "deno.json", "Cargo.toml", "go.mod",
        "pom.xml", "build.gradle", "build.gradle.kts", "Gemfile", "composer.json",
        "mix.exs", "pubspec.yaml", "CMakeLists.txt", "Makefile", "Dockerfile",
        "AGENTS.md", "CLAUDE.md", ".cursorrules"
    );
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        ".py", ".pyi", ".ipynb", ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
        ".go", ".rs", ".java", ".kt", ".kts", ".scala", ".rb", ".php", ".c", ".h",
        ".cc", ".cpp", ".hpp", ".cs", ".swift", ".m", ".mm", ".dart", ".ex", ".exs",
        ".lua", ".sh", ".bash", ".zsh", ".sql", ".vue", ".svelte", ".r", ".jl",
        ".hs", ".clj", ".erl", ".pl"
    );
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "venv", ".venv", "__pycache__", "dist", "build",
        "target", ".next", ".turbo", "vendor"
    );
    private static final int MAX_SCAN_ENTRIES = 500;

    /**
     * Resolve coding posture. Modes match Hermes agent.coding_context:
     * {@code on} always coding; {@code off} always general; {@code auto} and
     * {@code focus} require an interactive surface and a detected workspace.
     */
    public boolean isCodingContext(String mode, String platform, String workingDir) {
        String normalizedMode = normalizeMode(mode);
        if ("off".equals(normalizedMode)) return false;
        if ("on".equals(normalizedMode)) return true;

        String surface = platform == null ? "" : platform.trim().toLowerCase();
        if (!INTERACTIVE_CODING_SURFACES.contains(surface)) return false;

        Path cwd = resolveDirectory(workingDir);
        if (cwd == null) return false;
        if (findMarkerRoot(cwd) != null) return true;

        Path gitRoot = findGitRoot(cwd);
        if (gitRoot != null && gitRoot.equals(homeDirectory())) {
            gitRoot = null; // Hermes: home dotfiles repo is not a coding workspace.
        }
        return gitRoot != null && hasCodeFiles(gitRoot);
    }

    static String normalizeMode(String mode) {
        if (mode == null) return "auto";
        String value = mode.trim().toLowerCase();
        return Set.of("auto", "focus", "on", "off").contains(value) ? value : "auto";
    }

    private Path resolveDirectory(String workingDir) {
        try {
            String chosen = workingDir == null || workingDir.isBlank()
                ? System.getProperty("user.dir") : workingDir;
            Path path = Path.of(chosen).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Path findMarkerRoot(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            for (String marker : PROJECT_MARKERS) {
                if (Files.exists(current.resolve(marker))) return current;
            }
        }
        return null;
    }

    private Path findGitRoot(Path start) {
        for (Path current = start; current != null; current = current.getParent()) {
            if (Files.isDirectory(current.resolve(".git")) || Files.isRegularFile(current.resolve(".git"))) {
                return current;
            }
        }
        return null;
    }

    private Path homeDirectory() {
        try {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /** Bounded two-level scan, matching Hermes _has_code_files. */
    private boolean hasCodeFiles(Path root) {
        int[] seen = {0};
        try (Stream<Path> paths = Files.walk(root, 2)) {
            return paths
                .filter(path -> {
                    if (seen[0]++ >= MAX_SCAN_ENTRIES) return false;
                    Path relative = root.relativize(path);
                    for (Path part : relative) {
                        if (SKIP_DIRS.contains(part.toString())) return false;
                    }
                    return Files.isRegularFile(path);
                })
                .anyMatch(this::isCodeFile);
        } catch (IOException e) {
            log.debug("Could not inspect coding workspace {}: {}", root, e.getMessage());
            return false;
        }
    }

    private boolean isCodeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(name.substring(dot));
    }
}