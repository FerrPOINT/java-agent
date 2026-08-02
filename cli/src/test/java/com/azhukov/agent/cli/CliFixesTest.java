package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for new C1-C8 features: model switching, stub fixes, new commands,
 * aliases, prefix matching, dynamic skills, and error handling.
 */
class CliFixesTest {

    private SlashCommandRegistry registry;
    private BackendClient client;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
    }

    // ── C1: Model switching ──

    @Test
    void modelCommandWithArgsCallsSwitchModel() {
        when(client.switchModel("sid", "gpt-4o", null)).thenReturn("Model switched to: gpt-4o");
        String result = registry.execute("/model gpt-4o", client, "sid");
        assertThat(result).contains("Model switched to: gpt-4o");
    }

    @Test
    void modelCommandWithModelAndProvider() {
        when(client.switchModel("sid", "gpt-4o", "openai")).thenReturn("Model switched to: gpt-4o (provider: openai)");
        String result = registry.execute("/model gpt-4o openai", client, "sid");
        assertThat(result).contains("gpt-4o");
        assertThat(result).contains("openai");
    }

    @Test
    void modelCommandWithNoArgsShowsCurrentModel() {
        when(client.getCurrentModel("sid")).thenReturn("Current model: gpt-4o");
        String result = registry.execute("/model", client, "sid");
        assertThat(result).contains("Current model");
    }

    // ── C2: Fixed stub commands ──

    @Test
    void backgroundCommandCallsBackend() {
        when(client.backgroundTask("do something", "sid")).thenReturn("Background task started. Session: abc");
        String result = registry.execute("/background do something", client, "sid");
        assertThat(result).contains("Background task started");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void branchCommandCallsBackend() {
        when(client.branchSession("sid", "my-branch")).thenReturn("Session branched: new-id");
        String result = registry.execute("/branch my-branch", client, "sid");
        assertThat(result).contains("Session branched");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void branchCommandWithoutNameCallsBackend() {
        when(client.branchSession("sid", null)).thenReturn("Session branched: sid");
        String result = registry.execute("/branch", client, "sid");
        assertThat(result).contains("Session branched");
    }

    @Test
    void cronCommandCallsBackend() {
        when(client.listCronJobs()).thenReturn("No cron jobs found.");
        String result = registry.execute("/cron", client, "sid");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void cronPauseCommandCallsBackend() {
        when(client.pauseCronJob("job-1")).thenReturn("Cron job paused: job-1");
        String result = registry.execute("/cron-pause job-1", client, "sid");
        assertThat(result).contains("Cron job paused");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void cronResumeCommandCallsBackend() {
        when(client.resumeCronJob("job-1")).thenReturn("Cron job resumed: job-1");
        String result = registry.execute("/cron-resume job-1", client, "sid");
        assertThat(result).contains("Cron job resumed");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void cronDeleteCommandCallsBackend() {
        when(client.deleteCronJob("job-1")).thenReturn("Cron job deleted: job-1");
        String result = registry.execute("/cron-delete job-1", client, "sid");
        assertThat(result).contains("Cron job deleted");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void memoryApproveCommandCallsBackend() {
        when(client.approveMemory("user1", "entry1")).thenReturn("Memory approved: entry1");
        String result = registry.execute("/memory-approve user1 entry1", client, "sid");
        assertThat(result).contains("Memory approved");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void memoryRejectCommandCallsBackend() {
        when(client.rejectMemory("user1", "entry1")).thenReturn("Memory rejected: entry1");
        String result = registry.execute("/memory-reject user1 entry1", client, "sid");
        assertThat(result).contains("Memory rejected");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void memoryDeleteCommandCallsBackend() {
        when(client.deleteMemory("user1", "entry1")).thenReturn("Memory deleted: entry1");
        String result = registry.execute("/memory-delete user1 entry1", client, "sid");
        assertThat(result).contains("Memory deleted");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void installCommandCallsBackend() {
        when(client.installBundle("my-bundle")).thenReturn("Bundle installed: my-bundle");
        String result = registry.execute("/install my-bundle", client, "sid");
        assertThat(result).contains("Bundle installed");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void uninstallCommandCallsBackend() {
        when(client.uninstallBundle("my-bundle")).thenReturn("Bundle uninstalled: my-bundle");
        String result = registry.execute("/uninstall my-bundle", client, "sid");
        assertThat(result).contains("Bundle uninstalled");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void deleteCheckpointCommandCallsBackend() {
        when(client.deleteCheckpoint("cp-1")).thenReturn("Checkpoint deleted: cp-1");
        String result = registry.execute("/delete-checkpoint cp-1", client, "sid");
        assertThat(result).contains("Checkpoint deleted");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void approvalsCommandCallsBackend() {
        when(client.listPendingApprovals()).thenReturn(null);
        // Mock the prettyPrint to handle null
        when(client.prettyPrint(null)).thenReturn("[]");
        String result = registry.execute("/approvals", client, "sid");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void approveToolCommandCallsBackend() {
        when(client.approveTool("sid")).thenReturn("Tool approved for session: sid");
        String result = registry.execute("/approve-tool sid", client, "sid");
        assertThat(result).contains("Tool approved");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void denyToolCommandCallsBackend() {
        when(client.denyTool("sid")).thenReturn("Tool denied for session: sid");
        String result = registry.execute("/deny-tool sid", client, "sid");
        assertThat(result).contains("Tool denied");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    // ── C3: New commands ──

    @Test
    void stopCommandIsRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("stop");
    }

    @Test
    void stopCommandCallsBackend() {
        when(client.stopAgent("sid")).thenReturn("Agent stopped.");
        String result = registry.execute("/stop", client, "sid");
        assertThat(result).contains("Agent stopped");
    }

    @Test
    void historyCommandIsRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("history");
    }

    @Test
    void goalCommandIsRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("goal");
    }

    @Test
    void goalCommandSetsGoal() {
        when(client.setGoal("sid", "Write tests")).thenReturn("Goal set: Write tests");
        String result = registry.execute("/goal Write tests", client, "sid");
        assertThat(result).contains("Goal set: Write tests");
    }

    @Test
    void goalCommandShowsCurrentGoal() {
        when(client.setGoal("sid", "Write tests")).thenReturn("Goal set: Write tests");
        when(client.getGoal("sid")).thenReturn("Current goal: Write tests");
        registry.execute("/goal Write tests", client, "sid");
        String result = registry.execute("/goal", client, "sid");
        assertThat(result).contains("Current goal: Write tests");
    }

    @Test
    void goalCommandWithNoGoalShowsMessage() {
        when(client.getGoal("fresh-sid")).thenReturn("No goal set");
        String result = registry.execute("/goal", client, "fresh-sid");
        assertThat(result).contains("No goal set");
    }

    @Test
    void resumeCommandIsRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("resume");
    }

    @Test
    void saveCommandIsRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("save");
    }

    // ── C4: Session persistence ──

    @Test
    void saveCommandReturnsMessage() {
        String result = registry.execute("/save", client, "test-session-id");
        assertThat(result).contains("Session saved");
        assertThat(result).contains("test-session-id");
    }

    // ── C7: Aliases ──

    @Test
    void aliasesAreRegistered() {
        var aliases = registry.getAliases();
        assertThat(aliases).containsEntry("q", "queue");
        assertThat(aliases).containsEntry("s", "steer");
        assertThat(aliases).containsEntry("c", "cron");
        assertThat(aliases).containsEntry("r", "reload");
        assertThat(aliases).containsEntry("d", "diff");
        assertThat(aliases).containsEntry("reset", "new");
        assertThat(aliases).containsEntry("fork", "branch");
        assertThat(aliases).containsEntry("bg", "background");
        assertThat(aliases).containsEntry("snap", "checkpoint");
    }

    @Test
    void aliasQResolvesToQueue() {
        assertThat(registry.resolveCommand("q")).isEqualTo("queue");
    }

    @Test
    void aliasResetResolvesToNew() {
        assertThat(registry.resolveCommand("reset")).isEqualTo("new");
    }

    @Test
    void aliasForkResolvesToBranch() {
        assertThat(registry.resolveCommand("fork")).isEqualTo("branch");
    }

    @Test
    void aliasBgResolvesToBackground() {
        assertThat(registry.resolveCommand("bg")).isEqualTo("background");
    }

    @Test
    void aliasSnapResolvesToCheckpoint() {
        assertThat(registry.resolveCommand("snap")).isEqualTo("checkpoint");
    }

    // ── C7: Prefix matching ──

    @Test
    void prefixMatchResolvesUniqueCommand() {
        // "hel" should match only "help"
        assertThat(registry.resolveCommand("hel")).isEqualTo("help");
    }

    @Test
    void prefixMatchResolvesUniqueCommandForRol() {
        // "rol" should match only "rollback"
        assertThat(registry.resolveCommand("rol")).isEqualTo("rollback");
    }

    @Test
    void prefixMatchReturnsNullForAmbiguous() {
        // "c" is now an alias for "cron", so test ambiguity with another prefix
        // "cr" matches "cron", "cron-pause", "cron-resume", "cron-delete", "cron-create" — ambiguous
        assertThat(registry.resolveCommand("cr")).isNull();
        // "ver" now matches both "version" and "verbose" — ambiguous
        assertThat(registry.resolveCommand("ver")).isNull();
    }

    @Test
    void prefixMatchExecutesCommand() {
        when(client.health()).thenReturn(true);
        // "hel" should execute /help via prefix matching
        String result = registry.execute("/hel", client, "sid");
        assertThat(result).contains("Available Commands");
    }

    @Test
    void prefixMatchForUniqueCommandWorks() {
        when(client.health()).thenReturn(true);
        // "vers" should match "version" (verbose starts with "ver" too, so "vers" is needed)
        String result = registry.execute("/vers", client, "sid");
        assertThat(result).contains("Java Agent CLI");
    }

    @Test
    void aliasExecutionWorks() {
        when(client.backgroundTask("test prompt", "sid")).thenReturn("Background task started. Session: abc");
        // /bg should alias to /background and execute with args
        String result = registry.execute("/bg test prompt", client, "sid");
        assertThat(result).contains("Background task started");
        assertThat(result).doesNotContain("Unknown command");
    }

    @Test
    void isSlashCommandRecognizesAliases() {
        assertThat(registry.isSlashCommand("/q")).isTrue();
        assertThat(registry.isSlashCommand("/bg")).isTrue();
        assertThat(registry.isSlashCommand("/fork")).isTrue();
    }

    @Test
    void isSlashCommandRecognizesPrefixMatches() {
        assertThat(registry.isSlashCommand("/hel")).isTrue();
        assertThat(registry.isSlashCommand("/rol")).isTrue();
        // "ver" is now ambiguous (version + verbose)
        assertThat(registry.isSlashCommand("/ver")).isFalse();
        // "vers" uniquely matches "version"
        assertThat(registry.isSlashCommand("/vers")).isTrue();
    }

    // ── C6: Dynamic skill commands ──

    @Test
    void registerDynamicSkillAddsCommand() {
        registry.registerDynamicSkill("my-skill");
        List<String> names = registry.getCommandNames();
        assertThat(names).contains("my-skill");
    }

    @Test
    void dynamicSkillCommandCallsBackend() {
        registry.registerDynamicSkill("my-skill");
        when(client.getSkillContent("my-skill")).thenReturn("Skill content here");
        String result = registry.execute("/my-skill", client, "sid");
        assertThat(result).isEqualTo("Skill content here");
    }

    @Test
    void clearDynamicSkillsRemovesCommands() {
        registry.registerDynamicSkill("skill-a");
        registry.registerDynamicSkill("skill-b");
        assertThat(registry.getCommandNames()).contains("skill-a", "skill-b");
        registry.clearDynamicSkills();
        assertThat(registry.getCommandNames()).doesNotContain("skill-a", "skill-b");
    }

    @Test
    void dynamicSkillNamesAreTracked() {
        registry.registerDynamicSkill("alpha");
        registry.registerDynamicSkill("beta");
        List<String> skillNames = registry.getDynamicSkillNames();
        assertThat(skillNames).containsExactly("alpha", "beta");
    }

    @Test
    void dynamicSkillDoesNotOverwriteExistingCommand() {
        // "help" is already registered; registering a dynamic skill "help" should be ignored
        registry.registerDynamicSkill("help");
        // Verify the original help command still works
        String result = registry.execute("/help", client, "sid");
        assertThat(result).contains("Available Commands");
    }

    // ── C8: Error handling ──

    @Test
    void backendUnavailableExceptionIsThrownByChatOnConnectionError() {
        // This is tested at the BackendClient level; here we verify the exception class exists
        BackendUnavailableException ex = new BackendUnavailableException("http://localhost:8090",
            new java.net.ConnectException("Connection refused"));
        assertThat(ex.getMessage()).contains("Backend unavailable");
        assertThat(ex.getMessage()).contains("http://localhost:8090");
        assertThat(ex.getBackendUrl()).isEqualTo("http://localhost:8090");
    }

    // ── Existing tests still pass with new registry ──

    @Test
    void registersAtLeast45Commands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).hasSizeGreaterThanOrEqualTo(45);
    }

    @Test
    void allExpectedNewCommandsAreRegistered() {
        List<String> names = registry.getCommandNames();
        // C3 new commands
        assertThat(names).contains("stop", "history", "goal", "resume");
        // C4 new command
        assertThat(names).contains("save");
        // C6 cron-create
        assertThat(names).contains("cron-create");
    }

    @Test
    void helpListsAliases() {
        String result = registry.execute("/help", client, "sid");
        assertThat(result).contains("Aliases");
        assertThat(result).contains("/q");
        assertThat(result).contains("→ /queue");
    }
}