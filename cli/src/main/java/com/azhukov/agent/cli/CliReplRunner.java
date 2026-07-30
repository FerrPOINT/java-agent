package com.azhukov.agent.cli;

import lombok.RequiredArgsConstructor;
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

            // P1-7: Context reference expander
            ContextReferenceExpander contextExpander = new ContextReferenceExpander();

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
                            // P1-2: Use markdown renderer
                            System.out.println(new MarkdownRenderer(true).render(result));
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

                // P1-7: Expand @-context references before sending to backend
                if (ContextReferenceExpander.hasReferences(line)) {
                    line = contextExpander.expand(line);
                }

                // P1-4: Record last user message for /retry
                commandRegistry.getCliState().setLastUserMessage(line);

                // P1-5: Record session in local store
                commandRegistry.getSessionStore().incrementMessages(sessionId);

                // Chat message — stream or blocking
                if (streaming) {
                    System.out.println();
                    StringBuilder fullResponse = new StringBuilder();
                    try {
                        // P1-2: Use streaming markdown renderer
                        MarkdownRenderer mdRenderer = new MarkdownRenderer(true);
                        MarkdownRenderer.StreamingRenderer streamRenderer =
                            new MarkdownRenderer.StreamingRenderer(mdRenderer, System.out::print);

                        backendClient.chatStream(line, sessionId,
                            streamRenderer::accept,
                            toolInfo -> {
                                System.out.println("\n  [" + toolInfo + "]");
                            },
                            () -> {
                                streamRenderer.flush();
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
                        System.out.println(new MarkdownRenderer(true).render(response));
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