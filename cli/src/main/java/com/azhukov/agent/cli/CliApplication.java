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
 *   <li>{@code --session.id=UUID} — session ID (default: random UUID)</li>
 *   <li>{@code --model=NAME} — model override (default: from backend config)</li>
 * </ul>
 * These are passed as Spring properties with prefix {@code cli.*} so that
 * {@link BackendProperties} picks them up via @ConfigurationProperties.
 */
@SpringBootApplication(scanBasePackages = "com.azhukov.agent.cli")
public class CliApplication {

    public static void main(String[] args) {
        // Translate --backend.url, --session.id, --model into cli.* properties
        String backendUrl = "http://localhost:8090";
        String sessionId = UUID.randomUUID().toString();
        String model = "";

        for (String arg : args) {
            if (arg.startsWith("--backend.url=")) {
                backendUrl = arg.substring("--backend.url=".length());
            } else if (arg.startsWith("--session.id=")) {
                sessionId = arg.substring("--session.id=".length());
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
            }
        }

        // Inject as Spring properties for @ConfigurationProperties binding
        System.setProperty("cli.backend-url", backendUrl);
        System.setProperty("cli.session-id", sessionId);
        if (!model.isBlank()) {
            System.setProperty("cli.model", model);
        }

        SpringApplication.run(CliApplication.class, args);
    }
}