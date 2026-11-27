package com.azhukov.agent.cli;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * CLI properties — backend URL, session ID, model override.
 * Populated from command-line args via CliApplication.
 * <p>
 * C4: Session persistence — --new-session flag forces a new session instead of
 * loading from ~/.java-agent-cli/session.txt
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cli")
public class CliProperties {

    private String backendUrl = System.getenv().getOrDefault("CLI_BACKEND_URL", "http://localhost:8090");
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private String profile = "default";
    private String sessionId;
    private String model;
    private boolean newSession = false;

    public void setProfile(String profile) {
        if (profile == null || !PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("Invalid Java Agent profile name.");
        }
        this.profile = profile;
    }

    boolean namedProfile() {
        return !"default".equals(profile);
    }

    Path stateDirectory() {
        Path home = Path.of(System.getProperty("user.home"), ".java-agent-cli");
        return namedProfile() ? home.resolve("profiles").resolve(profile) : home;
    }

    /**
     * API key for the backend auth filter ({@code agent.security.api-key} on
     * the server, or a per-user key issued via /api/v1/admin/users). When the
     * backend runs with auth disabled (dev), this stays empty.
     * Env override: CLI_API_KEY or AGENT_API_KEY.
     */
    private String apiKey = System.getenv().getOrDefault("AGENT_API_KEY",
        System.getenv().getOrDefault("CLI_API_KEY", ""));
}