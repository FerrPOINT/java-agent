package com.azhukov.agent.bot.media;

import java.nio.file.Path;

public final class AgentMediaPaths {

    private AgentMediaPaths() {
    }

    public static Path mediaDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "agent-media")
            .toAbsolutePath()
            .normalize();
    }
}
