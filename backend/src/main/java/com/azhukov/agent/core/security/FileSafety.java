package com.azhukov.agent.core.security;

import java.nio.file.Path;

public interface FileSafety {

    boolean isPathAllowed(Path path);

    boolean isCommandAllowed(String command);
}
