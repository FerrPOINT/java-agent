package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
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
 * P1-1: Multi-line input with Alt+Enter
 * P1-2: Streaming markdown rendering
 * P1-3: Destructive command confirmation
 * P1-5: Session DB persistence
 * P1-7: @-context reference expansion
 * P1-9: External editor support
 * P1-10: Input history persistence
 */
@Component
@Slf4j
public class CliReplRunner implements CommandLineRunner {

    private final BackendClient backendClient;
    private final SlashCommandRegistry commandRegistry;
    private final BackendProperties properties;
    private final MarkdownRenderer markdownRenderer;
    private final ContextReferenceExpander contextExpander;

    public CliReplRunner(BackendClient backendClient, SlashCommandRegistry commandRegistry,
                         BackendProperties properties, MarkdownRenderer markdownRenderer,
                         ContextReferenceExpander contextExpander) {
        this.backendClient = backendClient;
        this.commandRegistry = commandRegistry;
        this.properties = properties;
        this.markdownRenderer = markdownRenderer;
        this.contextExpander = contextExpander;
    }

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

        // C4: If still no session, create one via the backend so runtime settings endpoints work
        if (sessionId == null || sessionId.isBlank()) {
            try {
                sessionId = backendClient.createSession();
                if (sessionId == null || sessionId.isBlank()) {
                    sessionId = UUID.randomUUID().toString();
                }
            } catch (BackendUnavailableException e) {
                sessionId = UUID.randomUUID().toString();
            }
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
        System.out.println("  Alt+Enter: multi-line | /editor: external editor | @file: refs");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println();

        // C6: Load dynamic skill commands on startup
        loadDynamicSkills();

        // P1-5: Load session store and record current session
        commandRegistry.getSessionStore().recordSession(sessionId, null);

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            // C5: Wire SlashCompleter into LineReader
            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new SlashCompleter(commandRegistry))
                .build();

            // P1-1: Enable multi-line input — Alt+Enter inserts newline
            reader.setOpt(LineReader.Option.BRACKETED_PASTE);

            // P1-10: Attach file-based history persistence
            InputHistoryManager.attachHistory(reader);

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

                // P1-10: Persist input history
                InputHistoryManager.appendEntry(line);

                // P1-9: External editor — /editor or \e
                if (line.equals("/editor") || line.equals("\\e")) {
                    String edited = ExternalEditor.edit(line.equals("\\e") ? "" : line);
                    if (edited != null && !edited.isBlank()) {
                        line = edited.strip();
                        System.out.println("(editor) " + line);
                    } else {
                        System.out.println("(editor cancelled)");
                        continue;
                    }
                }

                if (line.startsWith("/")) {
                    // P1-3: Destructive command confirmation
                    if (DestructiveCommandConfirmation.isDestructiveLine(line)) {
                        var result = commandRegistry.getDestructiveConfirmation()
                            .evaluateWithPrompt(line, p -> {
                                try {
                                    System.out.print(p);
                                    return reader.readLine("");
                                } catch (Exception e) {
                                    return "cancel";
                                }
                            });
                        if (result == DestructiveCommandConfirmation.ConfirmResult.CANCEL) {
                            System.out.println("🟡 Command cancelled.");
                            continue;
                        }
                        // Strip skip tokens from args
                        if (DestructiveCommandConfirmation.hasSkipToken(
                                line.substring(1).strip())) {
                            String cmdName = DestructiveCommandConfirmation.getCommandName(line);
                            String cleanArgs = DestructiveCommandConfirmation.getCleanArgs(line);
                            line = "/" + cmdName + (cleanArgs.isBlank() ? "" : " " + cleanArgs);
                        }
                    }
                    try {
                        String result = commandRegistry.execute(line, backendClient, sessionId);
                        if (result != null && !result.isEmpty()) {
                            System.out.println(markdownRenderer.render(result));
                        }
                    } catch (BackendUnavailableException e) {
                        // C8: Backend unavailable — show friendly message
                        System.out.println("Backend unavailable. Is the backend running on " +
                            backendClient.getBackendUrl() + "?");
                        System.out.println("Command failed: " + e.getMessage());
                    }
                    continue;
                }

                // P1-7: Expand @-context references before sending to backend
                if (ContextReferenceExpander.hasReferences(line)) {
                    line = contextExpander.expand(line);
                }

                // P1-4: Record last user message for /retry
                CliState cliState = commandRegistry.getCliState();
                if (cliState != null) {
                    cliState.setLastUserMessage(line);
                }

                // P1-5: Record session in local store
                SessionStore sessionStore = commandRegistry.getSessionStore();
                if (sessionStore != null) {
                    sessionStore.recordSession(sessionId, null);
                    sessionStore.incrementMessages(sessionId);
                }

                if (streaming) {
                    // Plain text — send as chat message via streaming SSE (pass cliState)
                    handleChatStream(line, sessionId, cliState);
                } else {
                    handleChatNonStream(line, sessionId, cliState);
                }
            }
        }
    }

    private void handleChatStream(String message, String sessionId, CliState cliState) {
        MarkdownRenderer.StreamingRenderer renderer =
            new MarkdownRenderer.StreamingRenderer(markdownRenderer, System.out::println);
        try {
            backendClient.chatStream(message, sessionId, cliState,
                renderer::accept,
                toolInfo -> System.out.println("\n  [" + toolInfo + "]"),
                () -> {
                    renderer.flush();
                    System.out.println();
                }
            );
        } catch (BackendUnavailableException e) {
            System.out.println("\nBackend unavailable. Is the backend running on " +
                backendClient.getBackendUrl() + "?");
            System.out.println("Your message was not sent. Please retry when the backend is available.");
        }
    }

    private void handleChatNonStream(String message, String sessionId, CliState cliState) {
        try {
            String response = backendClient.chat(message, sessionId, cliState);
            System.out.println(markdownRenderer.render(response));
        } catch (BackendUnavailableException e) {
            System.out.println("\nBackend unavailable. Is the backend running on " +
                backendClient.getBackendUrl() + "?");
        }
    }

    private void loadDynamicSkills() {
        try {
            var skills = backendClient.getSkills();
            if (skills != null) {
                int added = 0;
                if (skills.isArray()) {
                    for (JsonNode skill : skills) {
                        String name = skill.isTextual() ? skill.asText() : skill.path("name").asText("");
                        if (!name.isBlank()) {
                            commandRegistry.registerDynamicSkill(name);
                            added++;
                        }
                    }
                } else {
                    skills.forEach(s -> {
                        String name = s.isTextual() ? s.asText() : s.path("name").asText("");
                        if (!name.isBlank()) {
                            commandRegistry.registerDynamicSkill(name);
                        }
                    });
                }
                if (added > 0) {
                    log.info("Loaded {} dynamic skill commands", added);
                }
            }
        } catch (Exception e) {
            // C8: Backend may be unavailable on startup; don't fail entirely
            log.warn("Failed to load dynamic skills on startup: {}", e.getMessage());
        }
    }

    private String loadSavedSession() {
        try {
            if (Files.exists(SESSION_FILE)) {
                String saved = Files.readString(SESSION_FILE).strip();
                try {
                    UUID.fromString(saved);
                    return saved;
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring invalid saved session id: {}", saved);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load session: {}", e.getMessage());
        }
        return null;
    }

    private void saveSession(String sessionId) {
        try {
            Files.createDirectories(SESSION_DIR);
            Files.writeString(SESSION_FILE, sessionId);
        } catch (IOException e) {
            log.warn("Failed to save session: {}", e.getMessage());
        }
    }
}
