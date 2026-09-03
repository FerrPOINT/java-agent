package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.RunControlScope;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.tools.terminal.TerminalTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class FileToolSafety {

    private static final Set<String> OPAQUE_DOCUMENT_EXTENSIONS = Set.of(
        ".doc", ".docx", ".docm",
        ".xls", ".xlsx", ".xlsm", ".xlsb",
        ".ppt", ".pps", ".pot", ".pptx", ".pptm", ".ppsx", ".ppsm",
        ".odt", ".ods", ".odp",
        ".rtf", ".epub"
    );

    private static final Set<String> BLOCKED_READ_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2",
        "/dev/null", "/dev/tcp"
    );

    private static final Set<String> BLOCKED_PROC_SUFFIXES = Set.of(
        "/fd/0", "/fd/1", "/fd/2",
        "/environ", "/cmdline",
        "/maps", "/smaps", "/smaps_rollup", "/numa_maps",
        "/mem", "/auxv", "/pagemap"
    );


    /** Agent instruction files: patch/write_file may not silently rewrite them. */
    private static final Set<String> PROTECTED_INSTRUCTION_FILES = Set.of(
        "AGENTS.md", "CLAUDE.md", ".cursorrules", "AGENTS.local.md"
    );

    static ToolResult ensureNotProtectedInstruction(Path path, String rawPath, Boolean crossProfile) {
        if (Boolean.TRUE.equals(crossProfile)) {
            return null;
        }
        String name = path == null ? "" : path.getFileName().toString();
        if (PROTECTED_INSTRUCTION_FILES.contains(name)) {
            return ToolResult.fail(
                "Access denied: '" + name + "' is a protected agent instruction file; "
                    + "rewrite requires explicit cross_profile=true or a terminal edit.");
        }
        return null;
    }

    private FileToolSafety() {
    }

    static FileSafety defaultSafety(AgentProperties properties) {
        return new DefaultFileSafety(properties);
    }

    static Path resolvePath(String rawPath, Session session) {
        Path path = Path.of(expandTilde(rawPath));
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        String base = sessionWorkdir(session);
        if (base == null || base.isBlank()) {
            return path.toAbsolutePath().normalize();
        }
        Path basePath = Path.of(expandTilde(base));
        if (!basePath.isAbsolute()) {
            basePath = basePath.toAbsolutePath();
        }
        return basePath.resolve(path).normalize();
    }

    static ToolResult ensureReadable(AgentProperties properties, FileSafety fileSafety, Path path, String rawPath) {
        ToolResult blockedSpecial = ensureNotBlockedReadPath(path, rawPath);
        if (blockedSpecial != null) {
            return blockedSpecial;
        }
        ToolResult configuredPathCheck = ensureWithinAllowedPaths(properties, path, rawPath);
        if (configuredPathCheck != null) {
            return configuredPathCheck;
        }
        ToolResult readBlock = ensureNotReadBlocked(fileSafety, path, rawPath);
        if (readBlock != null) {
            return readBlock;
        }
        try {
            if (Files.exists(path)) {
                Path realPath = path.toRealPath();
                blockedSpecial = ensureNotBlockedReadPath(realPath, rawPath);
                if (blockedSpecial != null) {
                    return blockedSpecial;
                }
                configuredPathCheck = ensureWithinAllowedPaths(properties, realPath, rawPath);
                if (configuredPathCheck != null) {
                    return configuredPathCheck;
                }
                ToolResult regularOrDirectoryCheck = ensureRegularFileOrDirectory(realPath, rawPath);
                if (regularOrDirectoryCheck != null) {
                    return regularOrDirectoryCheck;
                }
                return ensureNotReadBlocked(fileSafety, realPath, rawPath);
            }
        } catch (IOException e) {
            return ToolResult.fail("Failed to resolve path safely: " + rawPath + " - " + e.getMessage());
        }
        return null;
    }

    static boolean isReadable(AgentProperties properties, FileSafety fileSafety, Path path) {
        return ensureReadable(properties, fileSafety, path, path.toString()) == null;
    }

    static ToolResult ensureWritable(FileSafety fileSafety, Path path, String rawPath, Boolean crossProfile) {
        ToolResult writeCheck = ensurePathAllowed(fileSafety, path, rawPath);
        if (writeCheck != null) {
            return writeCheck;
        }
        Path resolvedTarget = path;
        try {
            resolvedTarget = resolveWritableTarget(path);
            if (!resolvedTarget.equals(path)) {
                writeCheck = ensurePathAllowed(fileSafety, resolvedTarget, rawPath);
                if (writeCheck != null) {
                    return writeCheck;
                }
            }
        } catch (IOException e) {
            return ToolResult.fail("Failed to resolve path safely: " + rawPath + " - " + e.getMessage());
        }
        if (!Boolean.TRUE.equals(crossProfile) && fileSafety != null) {
            ToolResult profileCheck = ensureInCurrentProfile(fileSafety, path);
            if (profileCheck != null) {
                return profileCheck;
            }
            if (!resolvedTarget.equals(path)) {
                return ensureInCurrentProfile(fileSafety, resolvedTarget);
            }
        }
        return null;
    }

    static ToolResult ensurePlainTextWriteAllowed(Path path, String rawPath) {
        String displayPath = rawPath == null || rawPath.isBlank() ? String.valueOf(path) : rawPath;
        String ext = extension(displayPath);
        if (OPAQUE_DOCUMENT_EXTENSIONS.contains(ext)) {
            return ToolResult.fail(
                "Refusing to write plain text to binary document '" + displayPath + "' (" + ext + "). "
                    + "A text write cannot produce a valid document container and would corrupt the file. "
                    + "Use document/spreadsheet/presentation tooling or a format-specific library to create or edit this document.");
        }
        if (".pdf".equals(ext)) {
            try {
                Path resolved = resolveWritableTarget(path);
                if (Files.isRegularFile(resolved)) {
                    return ToolResult.fail(
                        "Refusing to overwrite existing PDF '" + displayPath + "' with plain text. "
                            + "Use the pdf skill or a PDF library to modify it. Creating a new .pdf file is allowed.");
                }
            } catch (IOException e) {
                return ToolResult.fail("Failed to resolve path safely: " + displayPath + " - " + e.getMessage());
            }
        }
        return null;
    }

    static Path resolveWritableTarget(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute;
        java.util.List<Path> missing = new java.util.ArrayList<>();
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current.getFileName());
            current = current.getParent();
        }
        if (current == null) {
            return absolute;
        }
        Path resolved = current.toRealPath();
        for (int i = missing.size() - 1; i >= 0; i--) {
            resolved = resolved.resolve(missing.get(i).toString());
        }
        return resolved.normalize();
    }

    private static ToolResult ensurePathAllowed(FileSafety fileSafety, Path path, String rawPath) {
        if (fileSafety == null) {
            return null;
        }
        // Denylist surfaces (.ssh, .env, …) must be refused even when path
        // allow-listing is disabled — the denylist check precedes the
        // enabled-flag in isWriteAllowed.
        if (fileSafety instanceof DefaultFileSafety dfs && !dfs.isWriteAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories or not allowed: " + rawPath);
        }
        if (!fileSafety.isPathAllowed(path)) {
            return ToolResult.fail("Access denied: path is outside allowed directories or not allowed: " + rawPath);
        }
        return null;
    }

    private static ToolResult ensureNotReadBlocked(FileSafety fileSafety, Path path, String rawPath) {
        if (fileSafety != null && fileSafety.isReadBlocked(path)) {
            return ToolResult.fail("Reading this path is not allowed: " + rawPath);
        }
        return null;
    }

    private static ToolResult ensureNotBlockedReadPath(Path path, String rawPath) {
        String raw = rawPath == null ? "" : rawPath;
        String normalizedRaw = raw.replace('\\', '/');
        String normalizedPath = path == null ? "" : path.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (matchesBlockedReadPath(normalizedRaw) || matchesBlockedReadPath(normalizedPath)) {
            return ToolResult.fail("Device file blocked: " + rawPath);
        }
        return null;
    }

    private static boolean matchesBlockedReadPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        for (String blocked : BLOCKED_READ_PATHS) {
            if (matchesPathOrChild(normalized, blocked)) {
                return true;
            }
        }
        return matchesBlockedProcPath(normalized);
    }

    private static boolean matchesPathOrChild(String path, String blocked) {
        return path.equals(blocked)
            || path.startsWith(blocked + "/")
            || path.endsWith(blocked)
            || path.contains(":/" + blocked.substring(1));
    }

    private static boolean matchesBlockedProcPath(String path) {
        String procPath = normalizeEmbeddedAbsolute(path, "/proc/");
        if (!procPath.startsWith("/proc/")) {
            return false;
        }
        for (String suffix : BLOCKED_PROC_SUFFIXES) {
            if (procPath.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeEmbeddedAbsolute(String path, String marker) {
        int driveIndex = path.indexOf(":" + marker);
        if (driveIndex >= 0) {
            return path.substring(driveIndex + 1);
        }
        return path;
    }

    private static ToolResult ensureRegularFileOrDirectory(Path path, String rawPath) {
        if (Files.isRegularFile(path) || Files.isDirectory(path)) {
            return null;
        }
        return ToolResult.fail(
            "Cannot read '" + rawPath + "': not a regular file or directory. Reading it could block indefinitely.");
    }

    private static ToolResult ensureInCurrentProfile(FileSafety fileSafety, Path path) {
        var warning = fileSafety.getCrossProfileWarning(path);
        return warning.map(ToolResult::fail).orElse(null);
    }

    private static String extension(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    private static ToolResult ensureWithinAllowedPaths(AgentProperties properties, Path path, String rawPath) {
        if (properties == null || !properties.getSecurity().isFileSafetyEnabled()) {
            return null;
        }
        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (String base : allowed) {
            Path allowedPath = Path.of(base).toAbsolutePath().normalize();
            if (normalized.startsWith(allowedPath)) {
                return null;
            }
        }
        return ToolResult.fail("Access denied: path is outside allowed directories: " + rawPath);
    }

    private static String sessionWorkdir(Session session) {
        if (session == null) {
            return null;
        }
        String workdir = session.getMetadata(TerminalTool.META_WORKDIR);
        if (workdir != null && !workdir.isBlank()) {
            return workdir;
        }
        UUID controlSessionId = RunControlScope.controlSessionId(session);
        return TerminalTool.trackedCwd(controlSessionId);
    }

    private static String expandTilde(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        if ("~".equals(path)) {
            return System.getProperty("user.home", ".");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home", "."), path.substring(2)).toString();
        }
        return path;
    }
}
