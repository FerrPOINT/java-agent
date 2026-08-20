package com.azhukov.agent.cli;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    private String backendUrl = "http://localhost:8090";
    private String sessionId;
    private String model;
    private boolean newSession = false;
}