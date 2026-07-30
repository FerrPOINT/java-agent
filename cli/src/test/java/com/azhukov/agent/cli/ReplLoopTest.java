package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplLoopTest {

    @Mock
    private BackendClient backendClient;
    @Mock
    private SlashCommandRegistry commandRegistry;
    @Mock
    private org.jline.reader.LineReader reader;

    private MarkdownRenderer markdownRenderer;
    private ReplLoop replLoop;

    @BeforeEach
    void setUp() {
        markdownRenderer = new MarkdownRenderer(false); // dumb terminal for clean test output
        replLoop = new ReplLoop(backendClient, commandRegistry, markdownRenderer);
    }

    /**
     * Helper: configure the mock LineReader to return the given lines sequentially,
     * then throw EndOfFileException to end the loop.
     */
    private void mockReadLines(String... lines) {
        Iterator<String> it = List.of(lines).iterator();
        when(reader.readLine("> ")).thenAnswer(inv -> {
            if (it.hasNext()) {
                return it.next();
            }
            throw new org.jline.reader.EndOfFileException();
        });
    }

    @Test
    void slashCommandDispatchesToRegistry() {
        when(commandRegistry.execute(eq("/help"), any(), anyString()))
            .thenReturn("Help output");

        mockReadLines("/help", "/exit");

        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        verify(commandRegistry).execute(eq("/help"), eq(backendClient), eq("session-1"));
        assertThat(output).anyMatch(s -> s.contains("Help output"));
    }

    @Test
    void plainTextCallsChatStream() {
        doNothing().when(backendClient).chatStream(
            anyString(), anyString(), any(), any(), any()
        );

        mockReadLines("hello world", "/exit");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        verify(backendClient).chatStream(
            eq("hello world"),
            eq("session-1"),
            any(),
            any(),
            any()
        );
    }

    @Test
    void emptyLineIsSkipped() {
        mockReadLines("", "/exit");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        // Verify no chat calls were made (empty line was skipped)
        verify(backendClient, never()).chatStream(
            anyString(), anyString(), any(), any(), any()
        );
        verify(commandRegistry, never()).execute(eq(""), any(), anyString());
    }

    @Test
    void eofExitsLoop() {
        // Empty iterator → EndOfFileException on first read
        when(reader.readLine("> "))
            .thenThrow(new org.jline.reader.EndOfFileException());

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        assertThat(output).contains("Goodbye!");
    }

    @Test
    void welcomeBannerIsShown() {
        mockReadLines("/exit");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        assertThat(output).anyMatch(s -> s.contains("Java Agent CLI"));
        assertThat(output).anyMatch(s -> s.contains("/help"));
    }

    @Test
    void slashCommandWithArgsDispatchesCorrectly() {
        when(commandRegistry.execute(eq("/undo 3"), any(), anyString()))
            .thenReturn("Undid 3 messages.");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        mockReadLines("/undo 3", "/exit");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        verify(commandRegistry).execute(eq("/undo 3"), eq(backendClient), eq("session-1"));
        assertThat(output).anyMatch(s -> s.contains("Undid 3 messages."));
    }

    @Test
    void chatStreamReceivesTokens() {
        // Simulate chatStream calling onToken for each token
        doAnswer(inv -> {
            var onToken = inv.getArgument(2, java.util.function.Consumer.class);
            onToken.accept("Hello");
            onToken.accept(" world");
            inv.getArgument(4, Runnable.class).run();
            return null;
        }).when(backendClient).chatStream(
            eq("test"), anyString(), any(), any(), any()
        );

        mockReadLines("test", "/exit");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        assertThat(output).contains("Hello");
        assertThat(output).contains(" world");
    }

    @Test
    void nullFromSlashCommandProducesNoOutput() {
        when(commandRegistry.execute(eq("/help"), any(), anyString()))
            .thenReturn(null);
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        mockReadLines("/help", "/exit");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        verify(commandRegistry).execute(eq("/help"), eq(backendClient), eq("session-1"));
    }

    @Test
    void emptyResultFromSlashCommandProducesNoOutput() {
        when(commandRegistry.execute(eq("/clear"), any(), anyString()))
            .thenReturn("");
        when(commandRegistry.execute(eq("/exit"), any(), anyString()))
            .thenReturn("Goodbye!");

        mockReadLines("/clear", "/exit");

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        verify(commandRegistry).execute(eq("/clear"), eq(backendClient), eq("session-1"));
    }

    @Test
    void nullReadLineExitsLoop() {
        when(reader.readLine("> "))
            .thenReturn(null);

        List<String> output = new ArrayList<>();
        replLoop.runLoop(reader, "session-1", output::add);

        assertThat(output).contains("Goodbye!");
    }
}