package com.azhukov.agent.service;

import com.azhukov.agent.core.security.SecureFileAccess;
import com.azhukov.agent.persistence.entity.McpServerConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** A named profile's accepted execution settings, isolated from later edits. */
@Service
@RequiredArgsConstructor
public class ProfileExecutionConfiguration {
    public static final String REVISION_HEADER = "X-Java-Agent-Profile-Revision";
    private static final Path REVISION_KEY = Path.of(".execution-revision-key");
    private static final ScopedValue<Snapshot> ACTIVE = ScopedValue.newInstance();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProfileService profiles;
    private final ProfileEnvStore environment;
    private final ProfileInstructions instructions;
    private final ProfileSkills skills;
    private final McpConfigStore mcp;
    private final ProfileRuntimeRegistry runtime;
    private final ObjectMapper json;

    public Snapshot capture(String profile) {
        if (!profiles.isValidNamedProfileName(profile)) {
            throw new IllegalArgumentException("A named Java Agent profile is required");
        }
        return runtime.locked(profile, () -> {
            try {
                String yaml = profiles.readRawConfig(profile);
                Map<String, Object> config = yaml.isBlank() ? Map.of() : ProfileService.parseExecutionConfig(yaml);
                Map<String, String> env = Map.copyOf(environment.readDisk(profile));
                var prompt = instructions.read(profile, config);
                var localSkills = skills.localEntries(profile, prompt);
                var mcpBindings = new TreeMap<String, McpVersion>();
                for (var server : mcp.list(profile)) {
                    mcpBindings.put(server.getName(), McpVersion.from(server));
                }
                var identity = new LinkedHashMap<String, Object>();
                identity.put("profile", profile);
                identity.put("yaml", yaml);
                identity.put("environment", new TreeMap<>(env));
                identity.put("base_role", prompt.base().roleText());
                if (prompt.base().verified() != null) {
                    identity.put("base_manifest", prompt.base().verified().manifestSha256());
                }
                identity.put("base_skills", new TreeMap<>(prompt.base().skills()));
                identity.put("persona", prompt.persona());
                identity.put("project", prompt.project());
                identity.put("project_override", prompt.projectOverride());
                var skillTexts = new TreeMap<String, String>();
                localSkills.forEach((name, entry) -> skillTexts.put(name, entry.info().content()));
                identity.put("local_skills", skillTexts);
                identity.put("mcp", mcpBindings);
                String revision = revision(profiles.profilePath(profile), json.writeValueAsBytes(identity));
                return new Snapshot(profile, revision, yaml, env, prompt,
                    Map.copyOf(localSkills), Map.copyOf(mcpBindings));
            } catch (IOException | RuntimeException | GeneralSecurityException e) {
                throw new ConfigurationUnavailable();
            }
        });
    }

    public static Snapshot current(String profile) {
        if (!ACTIVE.isBound()) {
            return null;
        }
        Snapshot snapshot = ACTIVE.get();
        if (!snapshot.profile.equals(profile)) {
            throw new IllegalArgumentException("Execution settings belong to another profile");
        }
        return snapshot;
    }

    public static boolean validRevision(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public static String currentRevision(String profile) {
        Snapshot snapshot = current(profile);
        return snapshot == null ? null : snapshot.revision();
    }

    public static Runnable carry(Runnable action) {
        if (!ACTIVE.isBound()) {
            return action;
        }
        Snapshot snapshot = ACTIVE.get();
        return () -> ScopedValue.where(ACTIVE, snapshot).run(action);
    }

    public static <T> Supplier<T> carrySupplier(Supplier<T> action) {
        if (!ACTIVE.isBound()) {
            return action;
        }
        Snapshot snapshot = ACTIVE.get();
        return () -> snapshot.use(action);
    }

    public static void requireAcceptedMcpVersion(String profile, McpServerConfigEntity server) {
        Snapshot snapshot = current(profile);
        if (snapshot != null && !McpVersion.from(server).equals(snapshot.mcpBindings.get(server.getName()))) {
            throw new IllegalStateException("MCP configuration changed after the run was accepted");
        }
    }

    private static String revision(Path root, byte[] configuration) throws IOException, GeneralSecurityException {
        Path file = root.resolve(REVISION_KEY);
        String encoded;
        try {
            encoded = SecureFileAccess.readUtf8(file);
        } catch (NoSuchFileException absent) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String candidate = Base64.getEncoder().encodeToString(random);
            try (var directory = SecureFileAccess.openDirectory(root)) {
                try {
                    directory.createFile(REVISION_KEY, candidate.getBytes(StandardCharsets.UTF_8));
                } catch (FileAlreadyExistsException concurrent) {
                    // Another process already established this profile's identity.
                }
            }
            encoded = SecureFileAccess.readUtf8(file);
        }
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) {
            throw new IllegalStateException("Invalid profile revision key");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(configuration));
    }

    private record McpVersion(UUID id, long revision) {
        static McpVersion from(McpServerConfigEntity server) {
            return new McpVersion(server.getId(), server.getConfigRevision());
        }
    }

    public static final class Snapshot {
        private final String profile;
        private final String revision;
        private final String yaml;
        private final Map<String, String> env;
        private final ProfileInstructions.Snapshot instructions;
        private final Map<String, ProfileSkills.Entry> skills;
        private final Map<String, McpVersion> mcpBindings;

        private Snapshot(String profile, String revision, String yaml, Map<String, String> env,
                         ProfileInstructions.Snapshot instructions, Map<String, ProfileSkills.Entry> skills,
                         Map<String, McpVersion> mcpBindings) {
            this.profile = profile;
            this.revision = revision;
            this.yaml = yaml;
            this.env = env;
            this.instructions = instructions;
            this.skills = skills;
            this.mcpBindings = mcpBindings;
        }

        public String revision() {
            return revision;
        }

        public ProfileInstructions.BasePackage baseInstructions() {
            return instructions.base();
        }

        Map<String, Object> settings() {
            return yaml.isBlank() ? new LinkedHashMap<>() : ProfileService.parseExecutionConfig(yaml);
        }

        Map<String, String> environment() {
            return env;
        }

        ProfileInstructions.Snapshot instructions() {
            return instructions;
        }

        Map<String, ProfileSkills.Entry> skills() {
            return skills;
        }

        public <T> T use(Supplier<T> operation) {
            return ScopedValue.where(ACTIVE, this).call(operation::get);
        }
    }

    public static final class ConfigurationUnavailable extends IllegalStateException {
        public ConfigurationUnavailable() {
            super("Profile execution configuration is unavailable");
        }
    }
}
