package com.azhukov.agent.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Main REPL loop for the CLI. Reads user input, dispatches slash commands,
 * and streams plain chat messages to the backend.
 */
@Slf4j
public class ReplLoop {

    private final BackendClient backendClient;
    private final SlashCommandRegistry commandRegistry;
    private final MarkdownRenderer markdownRenderer;
    private final ContextReferenceExpander contextExpander;

    private volatile boolean running = false;

    @Autowired
    public ReplLoop(BackendClient backendClient, SlashCommandRegistry commandRegistry,
                    MarkdownRenderer markdownRenderer, ContextReferenceExpander contextExpander) {
        this.backendClient = backendClient;
        this.commandRegistry = commandRegistry;
        this.markdownRenderer = markdownRenderer;
        this.contextExpander = contextExpander;
    }

    /**
     * Test-compatible entrypoint with a preconfigured LineReader.
     */
    public void runLoop(LineReader reader, String sessionId, Consumer<String> output) {
        running = true;
        outWelcome(output);
        while (running) {
            String line;
            try {
                line = reader.readLine("> ");
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (line == null) {
                output.accept("Goodbye!");
                break;
            }
            if (line.isBlank()) {
                continue;
            }
            line = line.strip();

            if (line.startsWith("/")) {
                if (DestructiveCommandConfirmation.isDestructive(line)) {
                    if (!DestructiveCommandConfirmation.hasSkipToken(line.substring(1).strip())) {
                        output.accept("This command is destructive. Use --yes to confirm.");
                        continue;
                    }
                    String cmdName = DestructiveCommandConfirmation.getCommandName(line);
                    String cleanArgs = DestructiveCommandConfirmation.getCleanArgs(line);
                    line = "/" + cmdName + (cleanArgs.isBlank() ? "" : " " + cleanArgs);
                }
                try {
                    String result = commandRegistry.execute(line, backendClient, sessionId);
                    if (result != null && !result.isEmpty()) {
                        output.accept(markdownRenderer.render(result));
                    }
                } catch (BackendUnavailableException e) {
                    output.accept("Backend unavailable. Is the backend running on " +
                        backendClient.getBackendUrl() + "?");
                    output.accept("Command failed: " + e.getMessage());
                }
                continue;
            }

            if (ContextReferenceExpander.hasReferences(line)) {
                line = contextExpander.expand(line);
            }

            CliState cliState = commandRegistry.getCliState();
            if (cliState != null) {
                cliState.setLastUserMessage(line);
            }
            SessionStore sessionStore = commandRegistry.getSessionStore();
            if (sessionStore != null) {
                sessionStore.recordSession(sessionId, null);
                sessionStore.incrementMessages(sessionId);
            }
            handleChat(line, sessionId, cliState, output);
        }
        running = false;
    }

    public void run(String sessionId, Consumer<String> output, InputStream in) {
        running = true;
        try (Terminal terminal = TerminalBuilder.builder()
                .streams(in, System.out)
                .system(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

            // C8: Bind Alt+Enter to insert a newline (multi-line input)
            KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
            main.bind(new org.jline.reader.Reference("self-insert"), "\033\r");
            main.bind(new org.jline.reader.Reference("self-insert"), "\033\n");

            runLoop(reader, sessionId, output);
        } catch (Exception e) {
            log.error("REPL error: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private void outWelcome(Consumer<String> output) {
        output.accept("═══════════════════════════════════════════════════");
        output.accept("  Java Agent CLI — type /help for commands, /exit to quit");
        output.accept("  Alt+Enter: newline | /editor: external editor | @file: refs");
        output.accept("═══════════════════════════════════════════════════");
    }

    private void handleChat(String message, String sessionId, CliState cliState, Consumer<String> output) {
        try {
            // P1-2: Use streaming markdown renderer instead of raw System.out.print
            MarkdownRenderer.StreamingRenderer streamRenderer =
                new MarkdownRenderer.StreamingRenderer(markdownRenderer, output);

            backendClient.chatStream(message, sessionId, cliState,
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
