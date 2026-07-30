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

/**
 * Interactive REPL loop for the CLI.
 * <p>
 * Reads input from the terminal via JLine. Lines starting with '/' are
 * dispatched to {@link SlashCommandRegistry}. All other lines are sent
 * as chat messages to the backend via streaming SSE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CliReplRunner implements CommandLineRunner {

    private final BackendClient backendClient;
    private final SlashCommandRegistry commandRegistry;
    private final BackendProperties properties;

    @Override
    public void run(String... args) throws Exception {
        String sessionId = properties.getSessionId();
        boolean streaming = true;

        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Java Agent CLI v0.0.1-SNAPSHOT");
        System.out.println("  Backend: " + properties.getBackendUrl());
        System.out.println("  Session: " + sessionId);
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  Type /help for commands, /exit to quit.");
        System.out.println("  Streaming: " + (streaming ? "ON" : "OFF") + " (use /stream to toggle)");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println();

        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
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
                    String result = commandRegistry.execute(line, backendClient, sessionId);
                    if (result != null && !result.isEmpty()) {
                        System.out.println(result);
                    }
                    System.out.println();
                    continue;
                }

                // Chat message — stream or blocking
                if (streaming) {
                    System.out.println();
                    StringBuilder fullResponse = new StringBuilder();
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
                } else {
                    System.out.println();
                    String response = backendClient.chat(line, sessionId);
                    System.out.println(response);
                    System.out.println();
                }
            }
        } catch (IOException e) {
            log.error("Terminal error: {}", e.getMessage());
            // Fall back to simple stdin reading
            System.err.println("JLine terminal unavailable, falling back to basic stdin.");
            fallbackRepl(sessionId, streaming);
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
                String result = commandRegistry.execute(line, backendClient, sessionId);
                if (result != null && !result.isEmpty()) {
                    System.out.println(result);
                }
                System.out.println();
                continue;
            }

            if (streaming) {
                System.out.println();
                backendClient.chatStream(line, sessionId,
                    System.out::print,
                    toolInfo -> System.out.println("\n  [" + toolInfo + "]"),
                    () -> System.out.println("\n")
                );
            } else {
                System.out.println();
                System.out.println(backendClient.chat(line, sessionId));
                System.out.println();
            }
        }
    }
}