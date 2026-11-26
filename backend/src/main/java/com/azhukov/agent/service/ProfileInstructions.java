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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    public record Snapshot(BasePackage base, String persona, Text project, String projectOverride, Set<String> disabledSkills) {}

    public record BasePackage(Path root, String roleText, Map<String, String> skills) {
        public String readSkill(String name) throws IOException {
            String expected = skills.get(name);
            if (expected == null) {
                return null;
            }
            return checked(root.resolve("skills").resolve(name).resolve("SKILL.md"), expected);
        }
    }

    private static final BasePackage NO_BASE = new BasePackage(null, "", Map.of());

    public Snapshot read(String profile) {
        try {
            Map<String, Object> config = profiles.readExecutionConfig(profile);
            BasePackage base = loadBase(config.get("instructions"));
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
            return new Snapshot(base, persona, project, optional(workspace.resolve("AGENTS.override.md")),
                disabledSkills(config));
        } catch (IOException | RuntimeException e) {
            // YAML parser errors can contain values from the rest of the profile config.
            throw new IllegalStateException("Unable to load instructions for the selected profile");
        }
    }

    public BasePackage loadBase(Object config) throws IOException {
        if (config == null) {
            return NO_BASE;
        }
        if (!(config instanceof Map<?, ?> section)) {
            throw new IllegalArgumentException("Profile instructions must be a mapping");
        }
        Object value = section.get("base_role");
        if (value == null) {
            return NO_BASE;
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
        JsonNode selected = manifest.path("roles").path(role);
        JsonNode instruction = selected.path("roleInstruction");
        String file = "roles/" + role + ".md";
        if (!file.equals(instruction.path("path").asText())) {
            throw new IllegalArgumentException("Base role is not in the pinned manifest");
        }
        String roleText = checked(root.resolve(file), instruction.path("sha256").asText());
        JsonNode hashes = manifest.path("sources").path("native").path("skills");
        Map<String, String> skills = new LinkedHashMap<>();
        for (JsonNode item : selected.path("physicalSkills")) {
            String name = item.asText();
            String digest = hashes.path(name).asText();
            if (!ProfileSkills.validName(name) || !SHA256.matcher(digest).matches()) {
                throw new IllegalArgumentException("Base skill is missing its pin");
            }
            skills.put(name, digest);
        }
        return new BasePackage(root, roleText, Map.copyOf(skills));
    }

    static Set<String> disabledSkills(Map<String, Object> config) {
        if (!(config.get("skills") instanceof Map<?, ?> section)) {
            return Set.of();
        }
        Object raw = section.get("disabled");
        var disabled = new TreeSet<String>();
        if (raw instanceof Iterable<?> names) {
            for (Object name : names) {
                if (name instanceof String text && !text.isBlank()) {
                    disabled.add(text.trim());
                }
            }
        } else if (raw instanceof String text) {
            for (String name : text.split(",")) {
                if (!name.isBlank()) {
                    disabled.add(name.trim());
                }
            }
        }
        return Set.copyOf(disabled);
    }

    private static String checked(Path path, String expected) throws IOException {
        String content = normalize(SecureFileAccess.readUtf8(path));
        if (content.isBlank() || !expected.equals(sha256(content))) {
            throw new IllegalArgumentException("Base file does not match its pin");
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
