package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlashCommandRegistryTest {

    private SlashCommandRegistry registry;
    private BackendClient client;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
    }

    @Test
    void registersAtLeast40Commands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void registersAllExpectedCommands() {
        List<String> names = registry.getCommandNames();

        // Core commands
        assertThat(names).contains("help", "exit", "quit");
        // Session commands
        assertThat(names).contains("new", "reset", "sessions", "status", "context", "compress", "undo");
        // Checkpoint commands
        assertThat(names).contains("checkpoint", "rollback", "checkpoints");
        // Memory & skills
        assertThat(names).contains("memory", "skills", "bundles");
        // Approve / deny
        assertThat(names).contains("approve", "deny");
        // Steer
        assertThat(names).contains("steer");
        // Admin
        assertThat(names).contains("restart", "reload-mcp", "reload-skills", "health");
        // Usage
        assertThat(names).contains("usage", "insights", "agents", "model", "version");
        // Memory management
        assertThat(names).contains("memory-all", "memory-pending", "memory-approve", "memory-reject", "memory-delete");
        // Tool approvals
        assertThat(names).contains("approvals", "approve-tool", "deny-tool");
        // Other
        assertThat(names).contains("clear", "branch", "background", "install", "uninstall");
        // Cron
        assertThat(names).contains("cron", "cron-pause", "cron-resume", "cron-delete");
    }

    @Test
    void helpListsAllCommands() {
        String result = registry.execute("/help", client, "session-1");
        assertThat(result).isNotNull();
        assertThat(result).contains("Available Commands");
        // Help should list every registered command
        for (String name : registry.getCommandNames()) {
            assertThat(result).contains("/" + name);
        }
    }

    @Test
    void exitReturnsExits() {
        // Note: /exit calls System.exit(0), which would kill the test JVM.
        // We can't test it directly, but we can test that the command is registered.
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("exit");
        assertThat(names).contains("quit");
    }

    @Test
    void healthCommandReturnsUpStatus() {
        when(client.health()).thenReturn(true);
        String result = registry.execute("/health", client, "sid");
        assertThat(result).contains("UP");
    }

    @Test
    void healthCommandReturnsDownStatus() {
        when(client.health()).thenReturn(false);
        String result = registry.execute("/health", client, "sid");
        assertThat(result).contains("DOWN");
    }

    @Test
    void resetCommandCallsBackend() {
        when(client.resetSession("sid")).thenReturn("Session reset: sid");
        String result = registry.execute("/reset", client, "sid");
        assertThat(result).isEqualTo("Session reset: sid");
    }

    @Test
    void unknownCommandReturnsErrorMessage() {
        String result = registry.execute("/nonexistent", client, "sid");
        assertThat(result).contains("Unknown command");
        assertThat(result).contains("/help");
    }

    @Test
    void emptySlashReturnsMessage() {
        String result = registry.execute("/", client, "sid");
        assertThat(result).contains("Empty command");
    }

    @Test
    void nullInputReturnsNull() {
        String result = registry.execute(null, client, "sid");
        assertThat(result).isNull();
    }

    @Test
    void nonSlashInputReturnsNull() {
        String result = registry.execute("hello world", client, "sid");
        assertThat(result).isNull();
    }

    @Test
    void isSlashCommandRecognizesKnownCommands() {
        assertThat(registry.isSlashCommand("/help")).isTrue();
        assertThat(registry.isSlashCommand("/exit")).isTrue();
        assertThat(registry.isSlashCommand("/reset")).isTrue();
    }

    @Test
    void isSlashCommandRejectsUnknownCommands() {
        assertThat(registry.isSlashCommand("/nonexistent")).isFalse();
        assertThat(registry.isSlashCommand("hello")).isFalse();
        assertThat(registry.isSlashCommand(null)).isFalse();
    }

    @Test
    void isSlashCommandHandlesArgs() {
        assertThat(registry.isSlashCommand("/undo 3")).isTrue();
        assertThat(registry.isSlashCommand("/compress focus-topic")).isTrue();
    }

    @Test
    void undoCommandParsesNumber() {
        when(client.undoTurns("sid", 3)).thenReturn("Undid 3 messages.");
        String result = registry.execute("/undo 3", client, "sid");
        assertThat(result).isEqualTo("Undid 3 messages.");
    }

    @Test
    void undoCommandDefaultsToOne() {
        when(client.undoTurns("sid", 1)).thenReturn("Undid 1 messages.");
        String result = registry.execute("/undo", client, "sid");
        assertThat(result).isEqualTo("Undid 1 messages.");
    }

    @Test
    void undoCommandHandlesInvalidNumber() {
        String result = registry.execute("/undo abc", client, "sid");
        assertThat(result).contains("Invalid number");
    }

    @Test
    void versionCommandReturnsVersionInfo() {
        when(client.health()).thenReturn(true);
        String result = registry.execute("/version", client, "sid");
        assertThat(result).contains("Java Agent CLI");
        assertThat(result).contains("0.0.1-SNAPSHOT");
        assertThat(result).contains("UP");
    }

    @Test
    void getCommandDescriptionReturnsDescription() {
        assertThat(registry.getCommandDescription("help")).contains("help");
        assertThat(registry.getCommandDescription("exit")).contains("Exit");
        assertThat(registry.getCommandDescription("nonexistent")).isEmpty();
    }

    @Test
    void getCommandDescriptionsReturnsAll() {
        var descriptions = registry.getCommandDescriptions();
        assertThat(descriptions).isNotEmpty();
        assertThat(descriptions.size()).isEqualTo(registry.getCommandNames().size());
        // Verify it's sorted
        var keys = new java.util.ArrayList<>(descriptions.keySet());
        var sortedKeys = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(sortedKeys);
        assertThat(keys).isEqualTo(sortedKeys);
    }
}