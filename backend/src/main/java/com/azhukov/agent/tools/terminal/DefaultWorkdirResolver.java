package com.azhukov.agent.tools.terminal;

import com.azhukov.agent.config.AgentProperties;

import java.io.File;

/**
 * Resolves the default working directory for terminal commands when no explicit
 * workdir and no tracked session cwd exist (first command of a session).
 * <p>
 * The JVM starts with the container WORKDIR (/app in the Docker image), which is
 * read-only in the hardened dev stack — inheriting it made every relative-path
 * write fail with EROFS ("Read-only file system"). Hermes defaults to the
 * session workspace instead and replaces unusable container cwds
 * (terminal_tool_config.py::_is_unusable_container_cwd); this resolver follows
 * that contract: configured working-directory first, then /workspace, user
 * home, tmpdir. Only existing, writable directories are returned; null means
 * "no candidate — let the caller keep the JVM default".
 */
public final class DefaultWorkdirResolver {

    /** Writable named volume mounted by both dev and prod compose files. */
    static final String CONTAINER_WORKSPACE = "/workspace";

    private DefaultWorkdirResolver() {
    }

    /**
     * First existing writable directory of: agent.core.working-directory,
     * /workspace, user.home, java.io.tmpdir. Null if none qualifies.
     */
    public static File resolveDefaultWorkdir(AgentProperties properties) {
        if (properties != null && properties.getCore() != null) {
            String configured = properties.getCore().getWorkingDirectory();
            if (configured != null && !configured.isBlank()) {
                File dir = new File(configured);
                if (isUsable(dir)) {
                    return dir;
                }
            }
        }
        for (String path : new String[] {CONTAINER_WORKSPACE,
                System.getProperty("user.home", ""),
                System.getProperty("java.io.tmpdir", "")}) {
            if (path == null || path.isBlank()) {
                continue;
            }
            File dir = new File(path);
            if (isUsable(dir)) {
                return dir;
                }
        }
        return null;
    }

    private static boolean isUsable(File dir) {
        return dir.isDirectory() && dir.canWrite();
    }
}
