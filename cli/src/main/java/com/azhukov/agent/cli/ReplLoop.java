package com.azhukov.agent.cli;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
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

        // C5: Build LineReader with SlashCompleter AND SlashAutoSuggest
        LineReader reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("java-agent-cli")
            .completer(new SlashCompleter(commandRegistry))
            .build();

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

            // Slash command — dispatch to registry
            if (line.startsWith("/")) {
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

            // Plain text — send as chat message via streaming SSE
            handleChat(line, sessionId, output);
        }

        running = false;
    }

    private void outWelcome(Consumer<String> output) {
        output.accept("═══════════════════════════════════════════════════");
        output.accept("  Java Agent CLI — type /help for commands, /exit to quit");
        output.accept("═══════════════════════════════════════════════════");
    }

    private void handleChat(String message, String sessionId, Consumer<String> output) {
        try {
            backendClient.chatStream(message, sessionId,
                // onToken — print token in real-time
                token -> {
                    output.accept(token);
                },
                // onTool — print tool events
                toolInfo -> output.accept("\n  [" + toolInfo + "]"),
                // onDone — newline after response
                () -> output.accept("")
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