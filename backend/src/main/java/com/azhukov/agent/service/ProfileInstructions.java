package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.SecureFileAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Loads the named profile's persona, workspace rules and explicitly selected Base role. */
@Service
@RequiredArgsConstructor
public class ProfileInstructions {
    private static final List<String> PROJECT_FILES =
        List.of("AGENTS.md", "agents.md", "CLAUDE.md", "claude.md", ".cursorrules");
    private static final Pattern ROLE = Pattern.compile("[a-z][a-z_]{0,47}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final AgentProperties properties;
    private final ProfileService profiles;
    private final ObjectMapper mapper;

    public record Text(String file, String content) {}
    public record Snapshot(String baseRole, String persona, Text project, String projectOverride) {}

    public Snapshot read(String profile) {
        try {
            Map<String, Object> config = profiles.readExecutionConfig(profile);
            String baseRole = loadBaseRole(config.get("instructions"));
            Path workspace = profiles.workspacePath(profile);
            var persona = optional(profiles.soulPath(profile));
            Text project = null;
            for (String file : PROJECT_FILES) {
                String content = optional(workspace.resolve(file));
                if (!content.isBlank()) {
                    project = new Text(file, content);
                    break;
                }
            }
            return new Snapshot(baseRole, persona, project, optional(workspace.resolve("AGENTS.override.md")));
        } catch (IOException | RuntimeException e) {
            // YAML parser errors can contain values from the rest of the profile config.
            throw new IllegalStateException("Unable to load instructions for the selected profile");
        }
    }

    private String loadBaseRole(Object config) throws IOException {
        if (config == null) {
            return "";
        }
        if (!(config instanceof Map<?, ?> section)) {
            throw new IllegalArgumentException("Profile instructions must be a mapping");
        }
        Object value = section.get("base_role");
        if (value == null) {
            return "";
        }
        if (!(value instanceof String role) || !ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("Invalid Base role");
        }
        String configuredDir = properties.getProfile().getBaseInstructionsDir();
        String expectedManifest = properties.getProfile().getBaseInstructionsManifestSha256();
        if (configuredDir == null || configuredDir.isBlank() || expectedManifest == null
            || !SHA256.matcher(expectedManifest).matches()) {
            throw new IllegalArgumentException("Base instructions require a configured directory and manifest pin");
        }
        Path root = Path.of(configuredDir);
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Base instructions require an absolute directory");
        }
        root = root.normalize();
        String manifestText = normalize(SecureFileAccess.readUtf8(root.resolve("manifest.json")));
        if (!expectedManifest.equals(sha256(manifestText))) {
            throw new IllegalArgumentException("Base instructions manifest does not match its pin");
        }
        JsonNode manifest = mapper.readTree(manifestText);
        if (!"base-hermes-role-skills/v1".equals(manifest.path("schema").asText())) {
            throw new IllegalArgumentException("Unsupported Base instructions manifest");
        }
        JsonNode instruction = manifest.path("roles").path(role).path("roleInstruction");
        String file = "roles/" + role + ".md";
        String expectedRole = instruction.path("sha256").asText();
        if (!file.equals(instruction.path("path").asText()) || !SHA256.matcher(expectedRole).matches()) {
            throw new IllegalArgumentException("Base role is not in the pinned manifest");
        }
        String content = normalize(SecureFileAccess.readUtf8(root.resolve(file)));
        if (content.isBlank() || !expectedRole.equals(sha256(content))) {
            throw new IllegalArgumentException("Base role instructions do not match their pin");
        }
        return content;
    }

    private static String optional(Path path) throws IOException {
        try {
            return SecureFileAccess.readUtf8(path);
        } catch (NoSuchFileException e) {
            return "";
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
