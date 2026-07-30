package com.azhukov.agent.cli;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.UUID;

/**
 * Spring Boot main class for the CLI subproject.
 * <p>
 * Accepts command-line args:
 * <ul>
 *   <li>{@code --backend.url=URL} — backend REST API base URL (default: http://localhost:8090)</li>
 *   <li>{@code --session.id=UUID} — session ID (default: random UUID or saved session)</li>
 *   <li>{@code --model=NAME} — model override (default: from backend config)</li>
 *   <li>{@code --new-session} — force a new session instead of loading from ~/.java-agent-cli/session.txt (C4)</li>
 * </ul>
 * These are passed as Spring properties with prefix {@code cli.*} so that
 * {@link BackendProperties} picks them up via @ConfigurationProperties.
 */
@SpringBootApplication(scanBasePackages = "com.azhukov.agent.cli")
public class CliApplication {

    public static void main(String[] args) {
        // Translate --backend.url, --session.id, --model, --new-session into cli.* properties
        String backendUrl = "http://localhost:8090";
        String sessionId = "";
        String model = "";
        boolean newSession = false;

        for (String arg : args) {
            if (arg.startsWith("--backend.url=")) {
                backendUrl = arg.substring("--backend.url=".length());
            } else if (arg.startsWith("--session.id=")) {
                sessionId = arg.substring("--session.id=".length());
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            } else if (arg.equals("--new-session") || arg.equals("--new-session=true")) {
                newSession = true;
            }
        }

        // If no session ID specified and not forcing new session, leave empty
        // so CliReplRunner can load from session.txt
        if (sessionId.isEmpty() && newSession) {
            sessionId = UUID.randomUUID().toString();
        }

        // Inject as Spring properties for @ConfigurationProperties binding
        System.setProperty("cli.backend-url", backendUrl);
        System.setProperty("cli.session-id", sessionId);
        if (!model.isBlank()) {
            System.setProperty("cli.model", model);
        }
        System.setProperty("cli.new-session", String.valueOf(newSession));

        SpringApplication.run(CliApplication.class, args);
    }
}