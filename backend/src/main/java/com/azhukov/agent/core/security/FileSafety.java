package com.azhukov.agent.core.security;

import java.nio.file.Path;

public interface FileSafety {

    boolean isPathAllowed(Path path);

    boolean isCommandAllowed(String command);

    /**
     * Checks if reading the given path should be blocked.
     * Blocks reads of sensitive credential files such as .env,
     * auth.json, .ssh/, .aws/credentials, .gnupg/, etc.
     *
     * @param path the path to check
     * @return true if the read should be blocked, false otherwise
     */
    default boolean isReadBlocked(Path path) {
        return false;
    }
}