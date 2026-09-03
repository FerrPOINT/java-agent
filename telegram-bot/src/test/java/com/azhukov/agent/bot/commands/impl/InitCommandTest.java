package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InitCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void nameAndDescription() {
        var cmd = new InitCommand(mock(BusySessionHandler.class));
        assertThat(cmd.name()).isEqualTo("init");
        assertThat(cmd.description()).contains("AGENTS.md");
    }

    @Test
    void noExistingFile_generatesFreshPrompt() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new InitCommand(handler);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("/init queued");
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.type() == Type.TEXT
                && e.text().contains("[/init]")
                && e.text().contains("AGENTS.md")));
    }

    @Test
    void promptCarriesQualityBar() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new InitCommand(handler);
        cmd.handle(makeEvent(""), null);
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.text().contains("QUALITY BAR")
                && e.text().contains("read_file")
                && e.text().contains("write_file")));
    }

    @Test
    void extraNotesAreCarried() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new InitCommand(handler);
        cmd.handle(makeEvent("focus on the test setup"), null);
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.text().contains("focus on the test setup")
                && e.text().contains("USER NOTES")));
    }

    @Test
    void existingAgentsMd_whenPresent_triggersMergeDiscipline() throws Exception {
        // Write an AGENTS.md into the JVM's working dir if we can control it.
        // The command reads System.getProperty("user.dir") — set it to tempDir.
        String original = System.getProperty("user.dir");
        try {
            Files.writeString(tempDir.resolve("AGENTS.md"), "# Existing rules\nUse spaces.");
            System.setProperty("user.dir", tempDir.toString());
            BusySessionHandler handler = mock(BusySessionHandler.class);
            var cmd = new InitCommand(handler);
            String result = cmd.handle(makeEvent(""), null);
            assertThat(result).contains("update the existing AGENTS.md");
            verify(handler).queueMessage(eq(123L), argThat(e ->
                e.text().contains("MERGE DISCIPLINE")
                    && e.text().contains("Use spaces.")));
        } finally {
            System.setProperty("user.dir", original);
        }
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", null, null, null, null, null, null, null, true, "init", args);
    }
}
