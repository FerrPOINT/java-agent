package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultFileSafety implements FileSafety {

    private final AgentProperties properties;

    // ─── Write denylist: sensitive files that are NEVER writable ───

    /** Filename suffixes that are always denied for writing. */
    private static final Set<String> DENYLIST_FILENAMES = Set.of(
            ".env",
            ".netrc",
            ".pgpass",
            ".npmrc",
            ".pypirc",
            ".git-credentials",
            "config.json"  // .docker/config.json — matched via path segment check too
    );

    /** Path segments that trigger deny when the normalized path contains them. */
    private static final Set<String> DENYLIST_SEGMENTS = Set.of(
            ".ssh",
            ".gnupg",
            ".aws",
            ".kube",
            ".docker"
    );

    /** Exact normalized sub-paths that are always denied. */
    private static final Set<String> DENYLIST_EXACT_SUFFIXES = Set.of(
            ".aws/credentials",
            ".kube/config",
            ".docker/config.json"
    );

    /** Absolute paths that are always denied. */
    private static final Set<String> DENYLIST_ABSOLUTE = Set.of(
            "/etc/sudoers",
            "/etc/shadow",
            "/etc/passwd"
    );

    // ─── Read-block list: sensitive files that should never be read ───

    private static final Set<String> READ_BLOCK_FILENAMES = Set.of(
            ".env",
            "auth.json",
            ".anthropic_oauth.json",
            "webhook_subscriptions.json",
            "google_oauth.json",
            "bws_cache.json"
    );

    private static final Set<String> READ_BLOCK_SEGMENTS = Set.of(
            ".ssh",
            ".gnupg"
    );

    private static final Set<String> READ_BLOCK_EXACT_SUFFIXES = Set.of(
            ".aws/credentials"
    );

    // ─── Path traversal protection ───

    private boolean hasTraversalBeforeNormalize(Path path) {
        if (path == null) return false;
        String str = path.toString();
        // Check for ".." as a path component before normalization
        for (Path element : path) {
            if (element.toString().equals("..")) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDenylist(Path normalized) {
        if (normalized == null) return false;
        String pathStr = normalized.toString();
        String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";

        // Check absolute paths
        for (String abs : DENYLIST_ABSOLUTE) {
            if (pathStr.equals(abs)) {
                return true;
            }
        }

        // Check filenames
        if (DENYLIST_FILENAMES.contains(fileName)) {
            return true;
        }

        // Check exact suffix sub-paths
        for (String suffix : DENYLIST_EXACT_SUFFIXES) {
            if (pathStr.endsWith("/" + suffix) || pathStr.endsWith(suffix)) {
                return true;
            }
        }

        // Check path segments (directories)
        for (Path element : normalized) {
            String seg = element.toString();
            if (DENYLIST_SEGMENTS.contains(seg)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isPathAllowed(Path path) {
        if (path == null) {
            return false;
        }
        if (!properties.getSecurity().isFileSafetyEnabled()) {
            return true;
        }

        // Path traversal protection: block paths with ".." components
        if (hasTraversalBeforeNormalize(path)) {
            // Allow only if the normalized result still passes — but we block
            // because traversal attempts are suspicious by default.
            // However, internal ".." that stays within bounds should be allowed.
            // We normalize and check if it's still within allowed base.
            Path normalized = path.toAbsolutePath().normalize();
            if (matchesDenylist(normalized)) {
                return false;
            }
            List<String> allowed = properties.getSecurity().getAllowedPaths();
            if (allowed == null || allowed.isEmpty()) {
                return true;
            }
            for (String allowedPath : allowed) {
                Path base = Paths.get(allowedPath).toAbsolutePath().normalize();
                if (normalized.startsWith(base)) {
                    return true;
                }
            }
            return false;
        }

        Path normalized = path.toAbsolutePath().normalize();

        // Denylist check — even inside allowed paths
        if (matchesDenylist(normalized)) {
            return false;
        }

        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        for (String allowedPath : allowed) {
            Path base = Paths.get(allowedPath).toAbsolutePath().normalize();
            if (normalized.startsWith(base)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isReadBlocked(Path path) {
        if (path == null) {
            return false;
        }
        if (!properties.getSecurity().isFileSafetyEnabled()) {
            return false;
        }

        Path normalized = path.toAbsolutePath().normalize();
        String pathStr = normalized.toString();
        String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";

        // Check filenames
        if (READ_BLOCK_FILENAMES.contains(fileName)) {
            return true;
        }

        // Check exact suffix sub-paths
        for (String suffix : READ_BLOCK_EXACT_SUFFIXES) {
            if (pathStr.endsWith("/" + suffix)) {
                return true;
            }
        }

        // Check path segments (directories)
        for (Path element : normalized) {
            String seg = element.toString();
            if (READ_BLOCK_SEGMENTS.contains(seg)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isCommandAllowed(String command) {
        if (!properties.getSecurity().isFileSafetyEnabled()) {
            return true;
        }
        List<String> blocked = properties.getSecurity().getBlockedCommands();
        if (blocked == null || command == null) {
            return true;
        }
        String lower = command.toLowerCase();
        for (String b : blocked) {
            if (lower.contains(b.toLowerCase())) {
                return false;
            }
        }
        return true;
    }
}