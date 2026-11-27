package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
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
@RequiredArgsConstructor
public class CliReplRunner implements CommandLineRunner {

    private final BackendClient backendClient;
    private final ProfileCliClient profileClient;
    private final SlashCommandRegistry commandRegistry;
    private final CliProperties properties;
    private final MarkdownRenderer markdownRenderer;
    private final ContextReferenceExpander contextExpander;

    @Override
    public void run(String... args) throws Exception {
        boolean named = properties.namedProfile();
        SessionStore store = commandRegistry.getSessionStore();
        CliState cliState = commandRegistry.getCliState();
        cliState.setUserProfile(properties.getProfile());
        Path historyFile = properties.stateDirectory().resolve("history.txt");
        if (named) {
            if (properties.getModel() != null && !properties.getModel().isBlank()) {
                throw new IllegalArgumentException("The selected profile determines the model.");
            }
            profileClient.capabilities();
            commandRegistry.selectProfile(profileClient, properties);
        }

        boolean newSessionRequested = properties.isNewSession();
        String sessionId = properties.getSessionId();
        boolean resumed = false;
        if ((sessionId == null || sessionId.isBlank()) && !newSessionRequested) {
            sessionId = store.loadActiveSession();
            resumed = sessionId != null;
        }
        if (named) {
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = profileClient.createSession();
            } else if (!profileClient.ownsSession(sessionId)) {
                throw new IllegalArgumentException("Session not found in this profile. Use --new-session.");
            }
            store.saveActiveSession(sessionId);
        } else if (sessionId == null || sessionId.isBlank()) {
            try {
                sessionId = backendClient.createSession();
            } catch (BackendUnavailableException e) {
                sessionId = null;
            }
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = UUID.randomUUID().toString();
            }
        }
        cliState.setCurrentSessionId(sessionId);
        if (!named) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    store.saveActiveSession(cliState.getCurrentSessionId());
                } catch (IOException e) {
                    log.warn("Failed to save session: {}", e.getMessage());
                }
            }));
        }

        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Java Agent CLI v" + getClass().getPackage().getImplementationVersion());
        System.out.println("  Backend: " + properties.getBackendUrl());
        System.out.println("  Session: " + sessionId);
        if (named) System.out.println("  Profile: " + properties.getProfile());
        if (resumed) {
            System.out.println("  (resumed from previous session)");
        }
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Type /help for commands, /exit to quit.");
        if (!named) {
            System.out.println("  Streaming: ON");
            System.out.println("  Alt+Enter: multi-line | /editor: external editor | @file: refs");
        }
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println();

        // C6: Load dynamic skill commands on startup
        if (!named) loadDynamicSkills();

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
            InputHistoryManager.attachHistory(reader, historyFile);

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
                InputHistoryManager.appendEntry(line, historyFile);

                // P1-9: External editor — /editor or \e
                if (!named && (line.equals("/editor") || line.equals("\\e"))) {
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
                    DestructiveCommandConfirmation destructiveConf = commandRegistry.getDestructiveConfirmation();
                    if (destructiveConf.isDestructiveLine(line)) {
                        var result = destructiveConf.evaluateWithPrompt(line, p -> {
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
                        if (destructiveConf.hasSkipToken(
                                line.substring(1).strip())) {
                            String cmdName = destructiveConf.getCommandName(line);
                            String cleanArgs = destructiveConf.getCleanArgs(line);
                            line = "/" + cmdName + (cleanArgs.isBlank() ? "" : " " + cleanArgs);
                        }
                    }
                    try {
                        String activeSessionId = cliState != null && cliState.getCurrentSessionId() != null
                            ? cliState.getCurrentSessionId()
                            : sessionId;
                        String result;
                        try {
                            result = commandRegistry.execute(line, backendClient, activeSessionId);
                        } catch (ExitCliException exitSignal) {
                            // m27: graceful exit — C4 session save + cleanup below the loop still run
                            System.out.println(exitSignal.getMessage());
                            break;
                        }
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
                if (!named && ContextReferenceExpander.hasReferences(line)) {
                    line = contextExpander.expand(line);
                }

                // P1-4: Record last user message for /retry
                if (cliState != null) {
                    cliState.setLastUserMessage(line);
                }

                String activeSessionId = cliState != null && cliState.getCurrentSessionId() != null
                    ? cliState.getCurrentSessionId()
                    : sessionId;
                SessionStore sessionStore = commandRegistry.getSessionStore();
                if (sessionStore != null) {
                    sessionStore.recordSession(activeSessionId, null);
                    sessionStore.incrementMessages(activeSessionId);
                }

                if (named) {
                    handleProfileChat(line, activeSessionId);
                } else {
                    handleChatStream(line, activeSessionId, cliState);
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
                () -> cliState.setQueuedPrompt(null)
            );
        } catch (BackendUnavailableException e) {
            System.out.println("\nBackend unavailable. Is the backend running on " +
                backendClient.getBackendUrl() + "?");
            System.out.println("The response could not be completed.");
        } finally {
            renderer.flush();
            System.out.println();
        }
    }

    private void handleProfileChat(String message, String sessionId) {
        var renderer = new MarkdownRenderer.StreamingRenderer(markdownRenderer, System.out::println);
        try {
            String run = profileClient.submit(sessionId, message);
            System.out.println("Run: " + run);
            String status = profileClient.awaitRun(run, renderer::accept,
                tool -> System.out.println("[" + tool + "]"));
            renderer.flush();
            if (!status.isEmpty()) System.out.println(status);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            System.out.println("Profile request failed (HTTP " + e.getStatusCode().value() + ").");
        } catch (RuntimeException e) {
            renderer.flush();
            System.out.println("Profile request failed: " + e.getMessage());
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
}
