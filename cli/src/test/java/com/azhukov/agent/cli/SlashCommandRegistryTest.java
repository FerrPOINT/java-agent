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
        // Session commands — "reset" is now an alias for "new" (C7)
        assertThat(names).contains("new", "sessions", "status", "context", "compress", "undo");
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
    void resetAliasCallsNewCommand() {
        // /reset is now an alias for /new (C7)
        when(client.createSession()).thenReturn("new-session-id");
        String result = registry.execute("/reset", client, "sid");
        assertThat(result).contains("New session started");
        assertThat(result).contains("new-session-id");
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
        // /reset is now an alias for /new, but still resolves
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

    // ── P1 Batch command tests ──

    @Test
    void registersAllP1BatchCommands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("diff", "reload", "credits", "curator", "kanban", "codex-runtime");
    }

    @Test
    void diffNoArgsShowsUsage() {
        String result = registry.execute("/diff", client, "sid");
        assertThat(result).contains("Usage: /diff");
    }

    @Test
    void diffOneArgShowsUsage() {
        String result = registry.execute("/diff abc", client, "sid");
        assertThat(result).contains("Usage: /diff");
    }

    @Test
    void diffTwoArgsCallsBackend() {
        when(client.diff("abc", "def")).thenReturn("No differences found.");
        String result = registry.execute("/diff abc def", client, "sid");
        assertThat(result).contains("No differences");
    }

    @Test
    void reloadCallsBackend() {
        when(client.reloadAll()).thenReturn("Skills and MCP servers reloaded.");
        String result = registry.execute("/reload", client, "sid");
        assertThat(result).contains("reloaded");
    }

    @Test
    void creditsCallsBackend() {
        when(client.getCredits()).thenReturn("Credits summary:\n  Total cost: $0.0");
        String result = registry.execute("/credits", client, "sid");
        assertThat(result).contains("Credits summary");
    }

    @Test
    void curatorStatusCallsBackend() {
        when(client.curatorStatus()).thenReturn("Curator status:\n  Enabled: true");
        String result = registry.execute("/curator", client, "sid");
        assertThat(result).contains("Curator status");
    }

    @Test
    void curatorRunCallsBackend() {
        when(client.curatorRun()).thenReturn("Curator cycle completed.");
        String result = registry.execute("/curator run", client, "sid");
        assertThat(result).contains("completed");
    }

    @Test
    void curatorInvalidSubcommandShowsUsage() {
        String result = registry.execute("/curator bogus", client, "sid");
        assertThat(result).contains("Usage: /curator");
    }

    @Test
    void kanbanListShowsEmptyMessage() {
        when(client.kanbanList()).thenReturn("Kanban board is empty.");
        String result = registry.execute("/kanban", client, "sid");
        assertThat(result).contains("empty");
    }

    @Test
    void kanbanAddWithoutTextShowsUsage() {
        String result = registry.execute("/kanban add", client, "sid");
        assertThat(result).contains("Usage: /kanban add");
    }

    @Test
    void kanbanAddWithTextCallsBackend() {
        when(client.kanbanAdd("test task")).thenReturn("Task added: test task (id: abc-123)");
        String result = registry.execute("/kanban add test task", client, "sid");
        assertThat(result).contains("Task added");
        assertThat(result).contains("abc-123");
    }

    @Test
    void kanbanDoneWithoutIdShowsUsage() {
        String result = registry.execute("/kanban done", client, "sid");
        assertThat(result).contains("Usage: /kanban done");
    }

    @Test
    void kanbanDoneWithIdCallsBackend() {
        when(client.kanbanDone("abc-123")).thenReturn("Task abc-123 marked done.");
        String result = registry.execute("/kanban done abc-123", client, "sid");
        assertThat(result).contains("marked done");
    }

    @Test
    void kanbanClearCallsBackend() {
        when(client.kanbanClear()).thenReturn("Kanban board cleared.");
        String result = registry.execute("/kanban clear", client, "sid");
        assertThat(result).contains("cleared");
    }

    @Test
    void kanbanInvalidSubcommandShowsUsage() {
        String result = registry.execute("/kanban bogus", client, "sid");
        assertThat(result).contains("Usage: /kanban");
    }

    @Test
    void codexRuntimeStatusCallsBackend() {
        when(client.codexRuntimeStatus()).thenReturn("Codex runtime:\n  Model: gpt-4o");
        String result = registry.execute("/codex-runtime", client, "sid");
        assertThat(result).contains("Codex runtime");
    }

    @Test
    void codexRuntimeModelWithoutNameShowsUsage() {
        String result = registry.execute("/codex-runtime model", client, "sid");
        assertThat(result).contains("Usage: /codex-runtime model");
    }

    @Test
    void codexRuntimeResetCallsBackend() {
        when(client.codexRuntimeReset()).thenReturn("Codex runtime reset.");
        String result = registry.execute("/codex-runtime reset", client, "sid");
        assertThat(result).contains("reset");
    }

    @Test
    void curatorPauseCallsBackend() {
        when(client.curatorPause()).thenReturn("Curator paused.");
        String result = registry.execute("/curator pause", client, "sid");
        assertThat(result).contains("paused");
    }

    @Test
    void curatorResumeCallsBackend() {
        when(client.curatorResume()).thenReturn("Curator resumed.");
        String result = registry.execute("/curator resume", client, "sid");
        assertThat(result).contains("resumed");
    }

    @Test
    void codexRuntimeModelWithNameCallsBackend() {
        when(client.codexRuntimeModel("gpt-4o")).thenReturn("Codex runtime model set: gpt-4o");
        String result = registry.execute("/codex-runtime model gpt-4o", client, "sid");
        assertThat(result).contains("gpt-4o");
    }

    // ── /new command tests ──

    @Test
    void newCommandCallsBackendCreateSession() {
        when(client.createSession()).thenReturn("backend-session-123");
        String result = registry.execute("/new", client, "old-session");
        assertThat(result).contains("New session started");
        assertThat(result).contains("backend-session-123");
    }

    @Test
    void newCommandWithExplicitSessionIdUsesIt() {
        String result = registry.execute("/new my-custom-session", client, "old-session");
        assertThat(result).contains("New session started");
        assertThat(result).contains("my-custom-session");
    }

    @Test
    void newCommandHandlesBackendFailure() {
        when(client.createSession()).thenReturn(null);
        String result = registry.execute("/new", client, "old-session");
        assertThat(result).contains("Failed to create new session");
    }

    // ── /goal command tests ──

    @Test
    void goalSetTextCallsBackendSetGoal() {
        when(client.setGoal("sid", "build a web app")).thenReturn("Goal set: build a web app");
        String result = registry.execute("/goal build a web app", client, "sid");
        assertThat(result).contains("Goal set");
        assertThat(result).contains("build a web app");
    }

    @Test
    void goalPauseCallsBackendPauseGoal() {
        when(client.pauseGoal("sid")).thenReturn("Goal paused.");
        String result = registry.execute("/goal pause", client, "sid");
        assertThat(result).contains("Goal paused");
    }

    @Test
    void goalResumeCallsBackendResumeGoal() {
        when(client.resumeGoal("sid")).thenReturn("Goal resumed.");
        String result = registry.execute("/goal resume", client, "sid");
        assertThat(result).contains("Goal resumed");
    }

    @Test
    void goalClearCallsBackendClearGoal() {
        when(client.clearGoal("sid")).thenReturn("Goal cleared.");
        String result = registry.execute("/goal clear", client, "sid");
        assertThat(result).contains("Goal cleared");
    }

    @Test
    void goalShowCallsBackendGetGoal() {
        when(client.getGoal("sid")).thenReturn("Current goal: build a web app");
        String result = registry.execute("/goal", client, "sid");
        assertThat(result).contains("Current goal");
    }

    @Test
    void goalShowNoGoalSet() {
        when(client.getGoal("sid")).thenReturn("No goal set.");
        String result = registry.execute("/goal", client, "sid");
        assertThat(result).contains("No goal set");
    }

    // ── /resume command tests ──

    @Test
    void resumeWithSessionIdSwitchesSession() {
        String result = registry.execute("/resume target-session-id", client, "old-session");
        assertThat(result).contains("Switched to session");
        assertThat(result).contains("target-session-id");
    }

    @Test
    void resumeWithSessionIdUpdatesCliState() {
        String result = registry.execute("/resume new-sess-123", client, "old-session");
        assertThat(result).contains("Switched to session: new-sess-123");
        assertThat(registry.getCliState().getCurrentSessionId()).isEqualTo("new-sess-123");
    }

    @Test
    void resumeWithoutArgsListsSessions() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode s1 = mapper.createObjectNode();
        s1.put("id", "sess-1");
        s1.put("title", "Test session");
        arr.add(s1);
        when(client.listSessions("user-1")).thenReturn(arr);
        String result = registry.execute("/resume", client, "sid");
        assertThat(result).contains("Available sessions");
        assertThat(result).contains("sess-1");
        assertThat(result).contains("Test session");
    }

    @Test
    void resumeWithoutArgsNoSessions() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(client.listSessions("default")).thenReturn(mapper.createArrayNode());
        String result = registry.execute("/resume", client, "sid");
        assertThat(result).contains("No sessions found");
    }
}