package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FileSafetyValidator {

    private final AgentProperties properties;
    private static final List<String> DEFAULT_BLOCKED_EXTENSIONS = List.of(".pem", ".key", ".p12", ".pfx", ".env");
    private static final List<String> SENSITIVE_NAMES = List.of(
        ".env", ".envrc", ".netrc", ".ssh/id", "id_ed25519", "id_rsa", "id_ecdsa", "id_dsa",
        "token", "secret", "password", "api_key", "apikey"
    );


    public String checkRead(Path path) {
        if (path == null) return "Path is null";
        String normalized = path.toAbsolutePath().normalize().toString();
        if (isSensitiveName(path.getFileName().toString())) {
            return "Read blocked for sensitive file: " + path.getFileName();
        }
        String allowedError = checkAllowedPath(normalized);
        if (allowedError != null) return allowedError;
        return null;
    }

    public String checkWrite(Path path) {
        if (path == null) return "Path is null";
        String normalized = path.toAbsolutePath().normalize().toString();
        if (isBlockedExtension(path)) {
            return "Write blocked for extension: " + getExtension(path);
        }
        if (isSensitiveName(path.getFileName().toString())) {
            return "Write blocked for sensitive file: " + path.getFileName();
        }
        String allowedError = checkAllowedPath(normalized);
        if (allowedError != null) return allowedError;
        return null;
    }

    private boolean isSensitiveName(String name) {
        String lower = name.toLowerCase();
        for (String s : SENSITIVE_NAMES) {
            if (lower.contains(s)) return true;
        }
        return false;
    }

    private boolean isBlockedExtension(Path path) {
        String ext = getExtension(path);
        List<String> configured = properties.getFile().getBlockedExtensions();
        List<String> blocked = configured.isEmpty() ? DEFAULT_BLOCKED_EXTENSIONS : configured;
        return blocked.contains(ext.toLowerCase());
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }

    private String checkAllowedPath(String normalized) {
        List<String> allowed = properties.getSecurity().getAllowedPaths();
        if (allowed.isEmpty() && properties.getFile().getAllowedPaths().isEmpty()) {
            return null;
        }
        List<String> all = new java.util.ArrayList<>(allowed);
        all.addAll(properties.getFile().getAllowedPaths());
        for (String base : all) {
            Path basePath = Paths.get(base).toAbsolutePath().normalize();
            if (normalized.startsWith(basePath.toString())) {
                return null;
            }
        }
        return "Path outside allowed roots: " + normalized;
    }
}