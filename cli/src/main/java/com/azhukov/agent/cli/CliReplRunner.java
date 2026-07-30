package com.azhukov.agent.cli;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.BufferImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Interactive REPL loop for the CLI.
 * <p>
 * Reads input from the terminal via JLine. Lines starting with '/' are
 * dispatched to {@link SlashCommandRegistry}. All other lines are sent
 * as chat messages to the backend via streaming SSE.
 * <p>
 * C4: Session persistence — saves/loads session ID to ~/.java-agent-cli/session.txt
 * C5: SlashAutoSuggest wired into LineReader for inline command suggestions
 * C6: Dynamic skill commands loaded on startup
 * C8: BackendUnavailableException handled with friendly messages
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CliReplRunner implements CommandLineRunner {

    private final BackendClient backendClient;
    private final SlashCommandRegistry commandRegistry;
    private final BackendProperties properties;

    private static final Path SESSION_DIR = Path.of(System.getProperty("user.home"), ".java-agent-cli");
    private static final Path SESSION_FILE = SESSION_DIR.resolve("session.txt");

    @Override
    public void run(String... args) throws Exception {
        // C4: Session persistence — check --new-session flag
        boolean newSessionRequested = false;
        for (String arg : args) {
            if ("--new-session".equals(arg) || "--new-session=true".equals(arg)) {
                newSessionRequested = true;
                break;
            }
        }

        String sessionId = properties.getSessionId();

        // C4: If no session ID was explicitly set, try to load from file
        if ((sessionId == null || sessionId.isBlank()) && !newSessionRequested) {
            sessionId = loadSavedSession();
            if (sessionId != null && !sessionId.isBlank()) {
                log.info("Resumed session: {}", sessionId);
            }
        }

        // C4: If still no session, generate a new one
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        // C4: Save session on exit
        final String finalSessionId = sessionId;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> saveSession(finalSessionId)));

        boolean streaming = true;

        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Java Agent CLI v0.0.1-SNAPSHOT");
        System.out.println("  Backend: " + properties.getBackendUrl());
        System.out.println("  Session: " + sessionId);
        // C4: Show if resumed
        if (loadSavedSession() != null && !newSessionRequested) {
            System.out.println("  (resumed from previous session)");
        }
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Type /help for commands, /exit to quit.");
        System.out.println("  Streaming: " + (streaming ? "ON" : "OFF") + " (use /stream to toggle)");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println();

        // C6: Load dynamic skill commands on startup
        loadDynamicSkills();

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            // C5: Wire SlashAutoSuggest into LineReader
            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new SlashCompleter(commandRegistry))
                .build();

            while (true) {
                String prompt = "agent> ";
                String line;
                try {
                    line = reader.readLine(prompt);
                } catch (UserInterruptException e) {
                    System.out.println("Use /exit to quit.");
                    continue;
                } catch (EndOfFileException e) {
                    System.out.println("Goodbye!");
                    break;
                }

                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("/")) {
                    try {
                        String result = commandRegistry.execute(line, backendClient, sessionId);
                        if (result != null && !result.isEmpty()) {
                            System.out.println(result);
                        }
                        System.out.println();
                    } catch (BackendUnavailableException e) {
                        // C8: Backend unavailable
                        System.out.println("Backend unavailable. Is the backend running on " +
                            properties.getBackendUrl() + "?");
                        System.out.println();
                    }
                    continue;
                }

                // Chat message — stream or blocking
                if (streaming) {
                    System.out.println();
                    StringBuilder fullResponse = new StringBuilder();
                    try {
                        backendClient.chatStream(line, sessionId,
                            token -> {
                                System.out.print(token);
                                fullResponse.append(token);
                            },
                            toolInfo -> {
                                System.out.println("\n  [" + toolInfo + "]");
                            },
                            () -> {
                                System.out.println("\n");
                            }
                        );
                    } catch (BackendUnavailableException e) {
                        // C8: Backend unavailable during chat
                        System.out.println("\nBackend unavailable. Is the backend running on " +
                            properties.getBackendUrl() + "?");
                        System.out.println("Your message was not sent. Please retry when the backend is available.\n");
                    }
                } else {
                    System.out.println();
                    try {
                        String response = backendClient.chat(line, sessionId);
                        System.out.println(response);
                        System.out.println();
                    } catch (BackendUnavailableException e) {
                        System.out.println("Backend unavailable. Is the backend running on " +
                            properties.getBackendUrl() + "?\n");
                    }
                }
            }
        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage());
            // Fall back to simple stdin reading
            System.err.println("JLine terminal unavailable, falling back to basic stdin.");
            fallbackRepl(sessionId, streaming);
        }
    }

    /**
     * C6: Load dynamic skill commands from backend.
     */
    private void loadDynamicSkills() {
        try {
            var skills = backendClient.getSkills();
            if (skills != null && skills.isArray()) {
                commandRegistry.clearDynamicSkills();
                for (var skillNode : skills) {
                    String skillName = skillNode.asText();
                    if (skillName != null && !skillName.isBlank()) {
                        commandRegistry.registerDynamicSkill(skillName);
                        log.debug("Registered dynamic skill command: /{}", skillName);
                    }
                }
                log.info("Loaded {} dynamic skill commands", skills.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load dynamic skills: {}", e.getMessage());
        }
    }

    /**
     * C4: Load saved session ID from ~/.java-agent-cli/session.txt
     */
    private String loadSavedSession() {
        try {
            if (Files.exists(SESSION_FILE)) {
                String saved = Files.readString(SESSION_FILE).strip();
                if (!saved.isBlank()) {
                    return saved;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load saved session: {}", e.getMessage());
        }
        return null;
    }

    /**
     * C4: Save session ID to ~/.java-agent-cli/session.txt
     */
    private void saveSession(String sessionId) {
        try {
            Files.createDirectories(SESSION_DIR);
            Files.writeString(SESSION_FILE, sessionId);
        } catch (Exception e) {
            log.warn("Failed to save session: {}", e.getMessage());
        }
    }

    private void fallbackRepl(String sessionId, boolean streaming) {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (true) {
            System.out.print("agent> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().strip();
            if (line.isEmpty()) continue;

            if (line.startsWith("/")) {
                try {
                    String result = commandRegistry.execute(line, backendClient, sessionId);
                    if (result != null && !result.isEmpty()) {
                        System.out.println(result);
                    }
                    System.out.println();
                    continue;
                } catch (BackendUnavailableException e) {
                    System.out.println("Backend unavailable. Is the backend running on " +
                        properties.getBackendUrl() + "?\n");
                    continue;
                }
            }

            if (streaming) {
                System.out.println();
                try {
                    backendClient.chatStream(line, sessionId,
                        System.out::print,
                        toolInfo -> System.out.println("\n  [" + toolInfo + "]"),
                        () -> System.out.println("\n")
                    );
                } catch (BackendUnavailableException e) {
                    System.out.println("Backend unavailable. Is the backend running on " +
                        properties.getBackendUrl() + "?\n");
                }
            } else {
                System.out.println();
                try {
                    System.out.println(backendClient.chat(line, sessionId));
                    System.out.println();
                } catch (BackendUnavailableException e) {
                    System.out.println("Backend unavailable. Is the backend running on " +
                        properties.getBackendUrl() + "?\n");
                }
            }
        }
    }
}