package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultFileSafety implements FileSafety {

    private final AgentProperties properties;


    @Override
    public boolean isPathAllowed(Path path) {
        if (!properties.getSecurity().isFileSafetyEnabled()) {
            return true;
        }
        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (String allowedPath : allowed) {
            Path base = Paths.get(allowedPath).toAbsolutePath().normalize();
            if (normalized.startsWith(base)) {
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