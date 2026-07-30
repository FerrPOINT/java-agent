package com.azhukov.agent.cli;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CLI properties — backend URL, session ID, model override.
 * Populated from command-line args via CliApplication.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cli")
public class BackendProperties {

    private String backendUrl = "http://localhost:8090";
    private String sessionId;
    private String model;

}