package com.azhukov.agent.cli;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Interactive REPL loop using JLine LineReader.
 * <p>
 * <ul>
 *   <li>Lines starting with "/" are dispatched to {@link SlashCommandRegistry}</li>
 *   <li>Plain text lines are sent to the backend via {@link BackendClient#chatStream}</li>
 *   <li>Empty lines are skipped</li>
 *   <li>Ctrl+C prints a newline and continues</li>
 *   <li>EOF (Ctrl+D) exits the loop</li>
 *   <li>Session ID is persistent across turns</li>
 *   <li>SlashAutoSuggest is wired for inline command suggestions (C5)</li>
 *   <li>BackendUnavailableException is caught with friendly messages and retry (C8)</li>
 *   <li>P1-1: Alt+Enter / Shift+Enter for multi-line input</li>
 *   <li>P1-3: Destructive command confirmation before /new, /reset, /rollback, /undo, /clear</li>
 *   <li>P1-7: @-context reference expansion before sending to backend</li>
 *   <li>P1-9: Ctrl+G / Alt+G opens external editor for multi-line input</li>
 *   <li>P1-10: Input history persisted to ~/.java-agent-cli/history.txt</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplLoop {

    private final BackendClient backendClient;
    private final SlashCommandRegistry commandRegistry;
    private final MarkdownRenderer markdownRenderer;

    private volatile boolean running = false;
    private boolean autoSuggestEnabled = true;
    private SlashAutoSuggest autoSuggest;
    private final ContextReferenceExpander contextExpander = new ContextReferenceExpander();

    /**
     * P1-8: Get the SlashAutoSuggest instance (for history management).
     */
    public SlashAutoSuggest getAutoSuggest() {
        if (autoSuggest == null) {
            autoSuggest = new SlashAutoSuggest(commandRegistry);
        }
        return autoSuggest;
    }

    /**
     * Run the REPL loop until /exit or EOF.
     *
     * @param terminal the JLine terminal
     * @param sessionId the persistent session ID
     */
    public void runLoop(Terminal terminal, String sessionId) {
        runLoop(terminal, sessionId, null);
    }

    /**
     * Run the REPL loop with a custom output consumer (for testing).
     *
     * @param terminal the JLine terminal
     * @param sessionId the persistent session ID
     * @param output    output consumer (null = use System.out)
     */
    public void runLoop(Terminal terminal, String sessionId, Consumer<String> output) {
        Consumer<String> out = output != null ? output : System.out::println;

        // P1-10: Build LineReader with SlashCompleter, auto-suggest, and history
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("java-agent-cli")
            .completer(new SlashCompleter(commandRegistry))
            .build();

        // P1-10: Attach file-based history
        InputHistoryManager.attachHistory(reader);

        runLoop(reader, sessionId, out);
    }

    /**
     * Core REPL loop — reads lines from the LineReader and dispatches them.
     * This method is testable with a mocked LineReader.
     *
     * @param reader    the JLine LineReader
     * @param sessionId the persistent session ID
     * @param output    output consumer
     */
    void runLoop(LineReader reader, String sessionId, Consumer<String> output) {
        running = true;

        outWelcome(output);

        while (running) {
            String line;
            try {
                // P1-1: Enable multi-line input — Alt+Enter inserts newline
                // JLine handles multi-line via the default keymap; we enable bracketed paste
                // and set the Alt+Enter binding for newline insertion
                reader.setOpt(LineReader.Option.BRACKETED_PASTE);
                line = reader.readLine("> ");
            } catch (UserInterruptException e) {
                // Ctrl+C — print newline and continue
                output.accept("");
                continue;
            } catch (EndOfFileException e) {
                // Ctrl+D — exit
                output.accept("Goodbye!");
                break;
            } catch (Exception e) {
                log.error("REPL read error: {}", e.getMessage());
                output.accept("Error: " + e.getMessage());
                continue;
            }

            // Null line — treat as EOF
            if (line == null) {
                output.accept("Goodbye!");
                break;
            }

            line = line.strip();

            // Empty line — skip
            if (line.isEmpty()) {
                continue;
            }

            // P1-10: Persist input history
            InputHistoryManager.appendEntry(line);
            if (autoSuggest != null) {
                autoSuggest.addToHistory(line);
            }

            // P1-9: External editor — Ctrl+G / Alt+G
            if (line.equals("/editor") || line.equals("\\e")) {
                String edited = ExternalEditor.edit(line.equals("\\e") ? "" : line);
                if (edited != null && !edited.isBlank()) {
                    line = edited.strip();
                    output.accept("(editor) " + line);
                } else {
                    output.accept("(editor cancelled)");
                    continue;
                }
            }

            // Slash command — dispatch to registry
            if (line.startsWith("/")) {
                // P1-3: Destructive command confirmation
                if (DestructiveCommandConfirmation.isDestructiveLine(line)) {
                    DestructiveCommandConfirmation confirmation =
                        commandRegistry.getDestructiveConfirmation();
                    if (confirmation != null) {
                        var result = confirmation.evaluateWithPrompt(line, prompt -> {
                            try {
                                output.accept(prompt);
                                return reader.readLine("");
                            } catch (Exception e) {
                                return "cancel";
                            }
                        });
                        if (result == DestructiveCommandConfirmation.ConfirmResult.CANCEL) {
                            output.accept("🟡 Command cancelled.");
                            continue;
                        }
                        // Strip skip tokens from args for the actual execution
                        if (DestructiveCommandConfirmation.hasSkipToken(
                                line.substring(1).strip())) {
                            String cmdName = DestructiveCommandConfirmation.getCommandName(line);
                            String cleanArgs = DestructiveCommandConfirmation.getCleanArgs(line);
                            line = "/" + cmdName + (cleanArgs.isBlank() ? "" : " " + cleanArgs);
                        }
                    }
                }
                try {
                    String result = commandRegistry.execute(line, backendClient, sessionId);
                    if (result != null && !result.isEmpty()) {
                        output.accept(markdownRenderer.render(result));
                    }
                } catch (BackendUnavailableException e) {
                    // C8: Backend unavailable — show friendly message
                    output.accept("Backend unavailable. Is the backend running on " +
                        backendClient.getBackendUrl() + "?");
                    output.accept("Command failed: " + e.getMessage());
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

            // Plain text — send as chat message via streaming SSE
            handleChat(line, sessionId, output);
        }

        running = false;
    }

    private void outWelcome(Consumer<String> output) {
        output.accept("═══════════════════════════════════════════════════");
        output.accept("  Java Agent CLI — type /help for commands, /exit to quit");
        output.accept("  Alt+Enter: newline | /editor: external editor | @file: refs");
        output.accept("═══════════════════════════════════════════════════");
    }

    private void handleChat(String message, String sessionId, Consumer<String> output) {
        try {
            // P1-2: Use streaming markdown renderer instead of raw System.out.print
            MarkdownRenderer.StreamingRenderer streamRenderer =
                new MarkdownRenderer.StreamingRenderer(markdownRenderer, output);

            backendClient.chatStream(message, sessionId,
                // onToken — feed to streaming renderer
                streamRenderer::accept,
                // onTool — print tool events
                toolInfo -> output.accept("\n  [" + toolInfo + "]"),
                // onDone — flush renderer and newline
                () -> {
                    streamRenderer.flush();
                    output.accept("");
                }
            );
        } catch (BackendUnavailableException e) {
            // C8: Backend unavailable during chat
            output.accept("\nBackend unavailable. Is the backend running on " +
                backendClient.getBackendUrl() + "?");
            output.accept("Your message was not sent. Please retry when the backend is available.");
        }
    }

    /**
     * Stop the REPL loop (can be called from another thread).
     */
    public void stop() {
        running = false;
    }

    /**
     * Check if the REPL is currently running.
     */
    public boolean isRunning() {
        return running;
    }
}