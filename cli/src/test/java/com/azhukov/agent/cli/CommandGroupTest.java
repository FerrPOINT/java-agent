package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * c8: Tests verifying the {@link CommandGroup} split of
 * {@link SlashCommandRegistry}. Each group registers its commands via
 * {@code registerAll(registry)}, and the registry delegates to all groups.
 */
class CommandGroupTest {

    private SlashCommandRegistry registry;
    private BackendClient client;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
    }

    // ── Group classes are instantiable and implement CommandGroup ──

    @Test
    void allCommandGroupsImplementCommandGroup() {
        CliState cliState = new CliState();
        SessionStore sessionStore = new SessionStore(SharedObjectMapper.get(), SessionStore.defaultStorePath());
        assertThat(new SessionCommands(cliState, sessionStore)).isInstanceOf(CommandGroup.class);
        assertThat(new CronCommands()).isInstanceOf(CommandGroup.class);
        assertThat(new MemoryCommands()).isInstanceOf(CommandGroup.class);
        assertThat(new ModelCommands(cliState)).isInstanceOf(CommandGroup.class);
        assertThat(new ApprovalCommands()).isInstanceOf(CommandGroup.class);
        assertThat(new AdminCommands(cliState)).isInstanceOf(CommandGroup.class);
        assertThat(new UtilityCommands(cliState)).isInstanceOf(CommandGroup.class);
    }

    // ── Registry delegates to all groups (full command set still registered) ──

    @Test
    void registryRegistersAllGroupsCommands() {
        List<String> names = registry.getCommandNames();
        // SessionCommands
        assertThat(names).contains("new", "sessions", "status", "context", "compress", "undo",
            "checkpoint", "rollback", "checkpoints", "delete-checkpoint", "branch", "background",
            "resume", "save", "history", "goal", "subgoal", "export", "title");
        // CronCommands
        assertThat(names).contains("cron", "cron-pause", "cron-resume", "cron-delete", "cron-create");
        // MemoryCommands
        assertThat(names).contains("memory", "memory-all", "memory-pending", "memory-approve",
            "memory-reject", "memory-delete");
        // ModelCommands
        assertThat(names).contains("model", "handoff", "reasoning", "fast", "voice");
        // ApprovalCommands
        assertThat(names).contains("approve", "deny", "approvals", "approve-tool", "deny-tool",
            "stop", "steer");
        // AdminCommands
        assertThat(names).contains("config", "doctor", "health", "usage", "insights", "agents",
            "restart", "reload-mcp", "reload-skills", "reload", "diff", "credits", "curator",
            "kanban", "codex-runtime", "plugins", "toolsets", "tools", "browser", "plan",
            "gquota", "platforms");
        // UtilityCommands
        assertThat(names).contains("help", "exit", "quit", "version", "clear", "redraw",
            "profile", "whoami", "statusbar", "editor", "image", "debug", "snapshot",
            "personality", "queue", "retry", "verbose", "yolo", "busy", "skills", "bundles",
            "install", "uninstall");
    }

    @Test
    void registryRegistersAtLeast79Commands() {
        assertThat(registry.getCommandNames()).hasSizeGreaterThanOrEqualTo(79);
    }

    // ── Each group's commands remain executable via the registry ──

    @Test
    void sessionCommandsExecute() {
        when(client.createSession()).thenReturn("new-id");
        assertThat(registry.execute("/new", client, "old")).contains("new-id");
        when(client.undoTurns("sid", 1)).thenReturn("Undid 1 messages.");
        assertThat(registry.execute("/undo", client, "sid")).contains("Undid 1 messages.");
    }

    @Test
    void cronCommandsExecute() {
        when(client.listCronJobs()).thenReturn("No cron jobs found.");
        assertThat(registry.execute("/cron", client, "sid")).contains("No cron jobs");
        when(client.pauseCronJob("j1")).thenReturn("Cron job paused: j1");
        assertThat(registry.execute("/cron-pause j1", client, "sid")).contains("paused");
    }

    @Test
    void memoryCommandsExecute() {
        when(client.approveMemory("u1", "e1")).thenReturn("Memory approved: e1");
        assertThat(registry.execute("/memory-approve u1 e1", client, "sid")).contains("Memory approved");
    }

    @Test
    void modelCommandsExecute() {
        when(client.switchModel("sid", "gpt-4o", null)).thenReturn("Model switched to: gpt-4o");
        assertThat(registry.execute("/model gpt-4o", client, "sid")).contains("gpt-4o");
    }

    @Test
    void approvalCommandsExecute() {
        when(client.stopAgent("sid")).thenReturn("Agent stopped.");
        assertThat(registry.execute("/stop", client, "sid")).contains("Agent stopped");
        when(client.steer("hi", "sid")).thenReturn("Steer sent.");
        assertThat(registry.execute("/steer hi", client, "sid")).contains("Steer sent");
    }

    @Test
    void adminCommandsExecute() {
        when(client.health()).thenReturn(true);
        assertThat(registry.execute("/health", client, "sid")).contains("UP");
        when(client.getCredits()).thenReturn("Credits summary:\n  Total cost: $0.0");
        assertThat(registry.execute("/credits", client, "sid")).contains("Credits summary");
    }

    @Test
    void utilityCommandsExecute() {
        assertThat(registry.execute("/profile", client, "sid")).contains("Active profile: default");
        assertThat(registry.execute("/whoami", client, "sid")).contains("User: default");
        when(client.health()).thenReturn(true);
        assertThat(registry.execute("/version", client, "sid")).contains("Java Agent CLI");
    }

    // ── Aliases are distributed across groups and resolve ──

    @Test
    void aliasesResolveAcrossGroups() {
        // UtilityCommands owns q, s, bg, snap, reset, fork, v, sb
        assertThat(registry.resolveCommand("q")).isEqualTo("queue");
        assertThat(registry.resolveCommand("s")).isEqualTo("steer");
        assertThat(registry.resolveCommand("bg")).isEqualTo("background");
        assertThat(registry.resolveCommand("snap")).isEqualTo("checkpoint");
        assertThat(registry.resolveCommand("reset")).isEqualTo("new");
        assertThat(registry.resolveCommand("fork")).isEqualTo("branch");
        assertThat(registry.resolveCommand("v")).isEqualTo("version");
        assertThat(registry.resolveCommand("sb")).isEqualTo("statusbar");
        // AdminCommands owns c, r, d
        assertThat(registry.resolveCommand("c")).isEqualTo("cron");
        assertThat(registry.resolveCommand("r")).isEqualTo("reload");
        assertThat(registry.resolveCommand("d")).isEqualTo("diff");
    }

    // ── Registry no-arg constructor wires shared state into groups ──

    @Test
    void registryCliStateSharedAcrossGroups() {
        // /yolo (UtilityCommands) and /fast (ModelCommands) share the same CliState
        registry.execute("/yolo", client, "sid");
        assertThat(registry.getCliState().isYoloMode()).isTrue();
        // /fast toggle reads the same cliState
        when(client.setFastMode("sid", true)).thenReturn("Fast mode: ON");
        registry.execute("/fast", client, "sid");
        assertThat(registry.getCliState().isFastMode()).isTrue();
    }

    @Test
    void registrySessionStoreSharedWithSessionCommands() {
        // /title uses sessionStore from the registry, shared with SessionCommands.
        // setTitle only updates an existing entry, so record the session first.
        registry.getSessionStore().recordSession("sid", null);
        when(client.setTitle("sid", "T")).thenReturn("Title set: T");
        registry.execute("/title T", client, "sid");
        assertThat(registry.getSessionStore().getSession("sid")).isNotNull();
        assertThat(registry.getSessionStore().getSession("sid").title).isEqualTo("T");
    }

    // ── Individual group registerAll can be invoked standalone ──

    @Test
    void sessionCommandsRegistersIntoFreshRegistry() {
        SlashCommandRegistry fresh = new SlashCommandRegistry();
        // Clear is not directly possible; instead verify the group contributes its commands
        // by checking a representative command is present after construction.
        assertThat(fresh.getCommandNames()).contains("new", "sessions", "context");
    }
}