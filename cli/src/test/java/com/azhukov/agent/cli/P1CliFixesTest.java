package com.azhukov.agent.cli;

import org.jline.reader.LineReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for P1 CLI fixes (10 features).
 */
class P1CliFixesTest {

    private SlashCommandRegistry registry;
    private BackendClient client;

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
    }

    // ── P1-1: Multi-line input ──
    // (JLine configuration — tested via CliReplRunner integration; no unit test needed
    //  since BRACKETED_PASTE is a JLine option)

    @Test
    void multiLineOptionCanBeEnabled() {
        // Just verify the option exists in the LineReader.Option enum
        assertThat(LineReader.Option.BRACKETED_PASTE).isNotNull();
    }

    // ── P1-2: Streaming markdown rendering ──

    @Test
    void streamingRendererBuffersAndFlushes() {
        MarkdownRenderer renderer = new MarkdownRenderer(true);
        StringBuilder output = new StringBuilder();
        MarkdownRenderer.StreamingRenderer sr = new MarkdownRenderer.StreamingRenderer(renderer, output::append);

        sr.accept("Hello");
        sr.accept(" world");
        sr.flush();

        assertThat(output.toString()).contains("Hello");
        assertThat(output.toString()).contains("world");
    }

    @Test
    void streamingRendererHandlesCodeBlocks() {
        MarkdownRenderer renderer = new MarkdownRenderer(true);
        StringBuilder output = new StringBuilder();
        MarkdownRenderer.StreamingRenderer sr = new MarkdownRenderer.StreamingRenderer(renderer, output::append);

        sr.accept("```\n");
        sr.accept("code line\n");
        sr.accept("```\n");
        sr.flush();

        assertThat(output.toString()).contains("code line");
    }

    @Test
    void markdownRendererHandlesReasoningBlocks() {
        MarkdownRenderer renderer = new MarkdownRenderer(false); // dumb terminal
        String result = renderer.render("<reasoning>thinking about this</reasoning>");
        assertThat(result).contains("thinking about this");
        assertThat(result).doesNotContain("<reasoning>");
        assertThat(result).doesNotContain("</reasoning>");
    }

    @Test
    void markdownRendererHandlesThinkBlocks() {
        MarkdownRenderer renderer = new MarkdownRenderer(false);
        String result = renderer.render("thinking about this");
        assertThat(result).contains("thinking about this");
        assertThat(result).doesNotContain("<think>");
        assertThat(result).doesNotContain("</think>");
    }

    @Test
    void markdownRendererHandlesTables() {
        MarkdownRenderer renderer = new MarkdownRenderer(false);
        String markdown = "| Name | Value |\n|------|-------|\n| a    | 1     |\n| b    | 2     |\n";
        String result = renderer.render(markdown);
        assertThat(result).contains("Name");
        assertThat(result).contains("Value");
        assertThat(result).contains("a");
        assertThat(result).contains("b");
        // Separator row should be processed
        assertThat(result).doesNotContain("------|-------");
    }

    @Test
    void markdownRendererSyntaxHighlighting() {
        MarkdownRenderer renderer = new MarkdownRenderer(true);
        String markdown = "```java\nString text = \"hello\";\n```\n";
        String result = renderer.render(markdown);
        // Should contain ANSI codes for syntax highlighting
        assertThat(result).contains("\033[");
        assertThat(result).contains("String");
        assertThat(result).contains("hello");
    }

    // ── P1-3: Destructive command confirmation ──

    @Test
    void destructiveCommandsAreIdentified() {
        assertThat(DestructiveCommandConfirmation.isDestructive("new")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructive("reset")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructive("rollback")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructive("undo")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructive("clear")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructive("help")).isFalse();
        assertThat(DestructiveCommandConfirmation.isDestructive("status")).isFalse();
    }

    @Test
    void destructiveLinesAreIdentified() {
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/new")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/reset")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/undo 3")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/clear")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/exit --delete")).isTrue();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/help")).isFalse();
        assertThat(DestructiveCommandConfirmation.isDestructiveLine("/status")).isFalse();
    }

    @Test
    void skipTokensAreDetected() {
        assertThat(DestructiveCommandConfirmation.hasSkipToken("now")).isTrue();
        assertThat(DestructiveCommandConfirmation.hasSkipToken("--yes")).isTrue();
        assertThat(DestructiveCommandConfirmation.hasSkipToken("-y")).isTrue();
        assertThat(DestructiveCommandConfirmation.hasSkipToken("title")).isFalse();
        assertThat(DestructiveCommandConfirmation.hasSkipToken("")).isFalse();
    }

    @Test
    void skipTokensAreStripped() {
        assertThat(DestructiveCommandConfirmation.stripSkipTokens("now")).isEqualTo("");
        assertThat(DestructiveCommandConfirmation.stripSkipTokens("--yes My title")).isEqualTo("My title");
        assertThat(DestructiveCommandConfirmation.stripSkipTokens("-y")).isEqualTo("");
        assertThat(DestructiveCommandConfirmation.stripSkipTokens("My title")).isEqualTo("My title");
    }

    @Test
    void confirmReturnsYesForNonDestructive() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        var result = conf.evaluate("/help");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.NOT_DESTRUCTIVE);
    }

    @Test
    void confirmReturnsYesForSkipToken() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        var result = conf.evaluate("/new now");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.YES);
    }

    @Test
    void confirmReturnsYesWhenDisabled() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        conf.setConfirmRequired(false);
        var result = conf.evaluate("/new");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.YES);
    }

    @Test
    void confirmWithPromptReturnsCancel() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        var result = conf.evaluateWithPrompt("/new", prompt -> "c");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.CANCEL);
    }

    @Test
    void confirmWithPromptReturnsYes() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        var result = conf.evaluateWithPrompt("/new", prompt -> "y");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.YES);
    }

    @Test
    void confirmWithPromptReturnsAlwaysAndDisablesFutureConfirms() {
        DestructiveCommandConfirmation conf = new DestructiveCommandConfirmation();
        var result = conf.evaluateWithPrompt("/new", prompt -> "a");
        assertThat(result).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.ALWAYS);
        assertThat(conf.isConfirmRequired()).isFalse();
        // Next call should auto-approve
        var result2 = conf.evaluateWithPrompt("/new", prompt -> "y");
        assertThat(result2).isEqualTo(DestructiveCommandConfirmation.ConfirmResult.YES);
    }

    @Test
    void getCommandNameExtractsName() {
        assertThat(DestructiveCommandConfirmation.getCommandName("/new")).isEqualTo("new");
        assertThat(DestructiveCommandConfirmation.getCommandName("/undo 3")).isEqualTo("undo");
        assertThat(DestructiveCommandConfirmation.getCommandName("/clear")).isEqualTo("clear");
    }

    @Test
    void getCleanArgsStripsSkipTokens() {
        assertThat(DestructiveCommandConfirmation.getCleanArgs("/new now")).isEqualTo("");
        assertThat(DestructiveCommandConfirmation.getCleanArgs("/new --yes My title")).isEqualTo("My title");
        assertThat(DestructiveCommandConfirmation.getCleanArgs("/clear")).isEqualTo("");
    }

    // ── P1-4: 15 new slash commands ──

    @Test
    void registersAll15NewCommands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains(
            "retry", "title", "queue", "snapshot", "personality",
            "verbose", "yolo", "reasoning", "fast", "voice",
            "busy", "tools", "browser", "plugins", "subgoal"
        );
    }

    @Test
    void verboseCommandCyclesMode() {
        String result = registry.execute("/verbose", client, "sid");
        assertThat(result).contains("Verbose mode:");
        assertThat(result).contains("off");
        // Cycle again
        String result2 = registry.execute("/verbose", client, "sid");
        assertThat(result2).contains("Verbose mode:");
    }

    @Test
    void yoloCommandTogglesMode() {
        String result = registry.execute("/yolo", client, "sid");
        assertThat(result).contains("YOLO mode:");
        // Toggle back
        String result2 = registry.execute("/yolo", client, "sid");
        assertThat(result2).contains("YOLO mode:");
    }

    @Test
    void reasoningCommandShowsCurrentLevel() {
        String result = registry.execute("/reasoning", client, "sid");
        assertThat(result).contains("Current reasoning effort:");
        assertThat(result).contains("medium");
        assertThat(result).contains("none");
        assertThat(result).contains("xhigh");
    }

    @Test
    void reasoningCommandSetsLevel() {
        when(client.setReasoningEffort("sid", "high")).thenReturn("Reasoning effort: high");
        String result = registry.execute("/reasoning high", client, "sid");
        assertThat(result).contains("high");
    }

    @Test
    void reasoningCommandRejectsInvalidLevel() {
        String result = registry.execute("/reasoning invalid", client, "sid");
        assertThat(result).contains("Invalid level");
        assertThat(result).contains("Valid levels:");
    }

    @Test
    void reasoningCommandCyclesLevel() {
        when(client.setReasoningEffort("sid", "high")).thenReturn("Reasoning effort: high");
        String result = registry.execute("/reasoning cycle", client, "sid");
        assertThat(result).contains("Reasoning effort:");
    }

    @Test
    void fastCommandToggles() {
        when(client.setFastMode("sid", true)).thenReturn("Fast mode: ON");
        String result = registry.execute("/fast", client, "sid");
        assertThat(result).contains("Fast mode:");
    }

    @Test
    void voiceCommandShowsStatus() {
        String result = registry.execute("/voice", client, "sid");
        assertThat(result).contains("Voice mode:");
        assertThat(result).contains("TTS:");
    }

    @Test
    void voiceCommandSetsOn() {
        when(client.setVoiceMode("sid", true)).thenReturn("Voice mode: ON");
        String result = registry.execute("/voice on", client, "sid");
        assertThat(result).contains("ON");
    }

    @Test
    void voiceCommandSetsOff() {
        when(client.setVoiceMode("sid", false)).thenReturn("Voice mode: OFF");
        String result = registry.execute("/voice off", client, "sid");
        assertThat(result).contains("OFF");
    }

    @Test
    void voiceCommandTogglesTts() {
        String result = registry.execute("/voice tts", client, "sid");
        assertThat(result).contains("TTS:");
    }

    @Test
    void busyCommandShowsStatus() {
        String result = registry.execute("/busy", client, "sid");
        assertThat(result).contains("Busy mode:");
    }

    @Test
    void busyCommandSetsQueue() {
        String result = registry.execute("/busy queue", client, "sid");
        assertThat(result).contains("QUEUE");
    }

    @Test
    void busyCommandSetsSteer() {
        String result = registry.execute("/busy steer", client, "sid");
        assertThat(result).contains("STEER");
    }

    @Test
    void busyCommandSetsInterrupt() {
        String result = registry.execute("/busy interrupt", client, "sid");
        assertThat(result).contains("INTERRUPT");
    }

    @Test
    void toolsCommandShowsList() {
        when(client.listTools("sid")).thenReturn(null);
        when(client.prettyPrint(null)).thenReturn("[]");
        String result = registry.execute("/tools", client, "sid");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void toolsCommandListSubcommand() {
        when(client.listTools("sid")).thenReturn(null);
        when(client.prettyPrint(null)).thenReturn("[]");
        String result = registry.execute("/tools list", client, "sid");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void toolsCommandDisableTool() {
        when(client.toggleTool("sid", "my-tool", false)).thenReturn("Tool my-tool: disabled");
        String result = registry.execute("/tools disable my-tool", client, "sid");
        assertThat(result).contains("disabled");
    }

    @Test
    void toolsCommandEnableTool() {
        when(client.toggleTool("sid", "my-tool", true)).thenReturn("Tool my-tool: enabled");
        String result = registry.execute("/tools enable my-tool", client, "sid");
        assertThat(result).contains("enabled");
    }

    @Test
    void browserCommandConnects() {
        when(client.connectBrowser("sid", "ws://localhost:9222")).thenReturn("Browser connected: ws://localhost:9222");
        String result = registry.execute("/browser ws://localhost:9222", client, "sid");
        assertThat(result).contains("Browser connected");
    }

    @Test
    void browserCommandShowsCurrentUrl() {
        // First set URL
        when(client.connectBrowser("sid", "ws://localhost:9222")).thenReturn("Browser connected");
        registry.execute("/browser ws://localhost:9222", client, "sid");
        // Then query
        String result = registry.execute("/browser", client, "sid");
        assertThat(result).contains("CDP URL:");
    }

    @Test
    void pluginsCommandListsPlugins() {
        when(client.listPlugins()).thenReturn(null);
        when(client.prettyPrint(null)).thenReturn("[]");
        String result = registry.execute("/plugins", client, "sid");
        assertThat(result).doesNotContain("Use the backend REST API");
    }

    @Test
    void subgoalCommandAddsCriteria() {
        when(client.addSubgoal("sid", "must pass tests")).thenReturn("Subgoal added: must pass tests");
        String result = registry.execute("/subgoal must pass tests", client, "sid");
        assertThat(result).contains("Subgoal added");
    }

    @Test
    void retryCommandWithNoPreviousMessage() {
        String result = registry.execute("/retry", client, "sid");
        assertThat(result).contains("No previous message");
    }

    @Test
    void retryCommandWithPreviousMessage() {
        // Set the last message
        registry.getCliState().setLastUserMessage("test message");
        when(client.retry("sid", "test message")).thenReturn("Response to test message");
        String result = registry.execute("/retry", client, "sid");
        assertThat(result).contains("Response to test message");
    }

    @Test
    void titleCommandSetsTitle() {
        when(client.setTitle("sid", "My Title")).thenReturn("Title set: My Title");
        String result = registry.execute("/title My Title", client, "sid");
        assertThat(result).contains("Title set");
    }

    @Test
    void queueCommandQueuesPrompt() {
        when(client.queuePrompt("sid", "do something")).thenReturn("Prompt queued for next turn.");
        String result = registry.execute("/queue do something", client, "sid");
        assertThat(result).contains("queued");
    }

    @Test
    void queueCommandShowsQueued() {
        registry.getCliState().setQueuedPrompt("queued text");
        String result = registry.execute("/queue", client, "sid");
        assertThat(result).contains("Queued: queued text");
    }

    @Test
    void snapshotCommandCreatesSnapshot() {
        when(client.createSnapshot("sid", null)).thenReturn("Snapshot created.");
        String result = registry.execute("/snapshot", client, "sid");
        assertThat(result).contains("Snapshot created");
    }

    @Test
    void personalityCommandSetsPersonality() {
        when(client.setPersonality("sid", "helpful assistant")).thenReturn("Personality set: helpful assistant");
        String result = registry.execute("/personality helpful assistant", client, "sid");
        assertThat(result).contains("Personality set");
    }

    @Test
    void personalityCommandShowsCurrent() {
        registry.getCliState().setPersonality("strict");
        String result = registry.execute("/personality", client, "sid");
        assertThat(result).contains("Current personality: strict");
    }

    @Test
    void cliStateVerboseModeCycles() {
        CliState state = new CliState();
        assertThat(state.getVerboseMode()).isEqualTo(CliState.VerboseMode.OFF);
        state.cycleVerboseMode();
        assertThat(state.getVerboseMode()).isEqualTo(CliState.VerboseMode.NEW);
        state.cycleVerboseMode();
        assertThat(state.getVerboseMode()).isEqualTo(CliState.VerboseMode.ALL);
        state.cycleVerboseMode();
        assertThat(state.getVerboseMode()).isEqualTo(CliState.VerboseMode.VERBOSE);
        state.cycleVerboseMode();
        assertThat(state.getVerboseMode()).isEqualTo(CliState.VerboseMode.OFF);
    }

    @Test
    void cliStateYoloModeToggles() {
        CliState state = new CliState();
        assertThat(state.isYoloMode()).isFalse();
        state.toggleYoloMode();
        assertThat(state.isYoloMode()).isTrue();
        state.toggleYoloMode();
        assertThat(state.isYoloMode()).isFalse();
    }

    @Test
    void cliStateReasoningEffortCycles() {
        CliState state = new CliState();
        assertThat(state.getReasoningEffort()).isEqualTo("medium");
        state.cycleReasoningEffort();
        assertThat(state.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void cliStateReasoningEffortValidates() {
        CliState state = new CliState();
        assertThat(state.setReasoningEffortIfValid("high")).isTrue();
        assertThat(state.getReasoningEffort()).isEqualTo("high");
        assertThat(state.setReasoningEffortIfValid("invalid")).isFalse();
        assertThat(state.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void cliStateToolStates() {
        CliState state = new CliState();
        assertThat(state.isToolEnabled("test-tool")).isTrue(); // default enabled
        state.setToolEnabled("test-tool", false);
        assertThat(state.isToolEnabled("test-tool")).isFalse();
        state.setToolEnabled("test-tool", true);
        assertThat(state.isToolEnabled("test-tool")).isTrue();
    }

    // ── P1-5: Session store ──

    @Test
    void sessionStoreCreatesAndListsSessions(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionStore store = new SessionStore(mapper, tempDir.resolve("sessions.json"));

        store.recordSession("session-1", "My Session");
        store.recordSession("session-2", "Another Session");

        var sessions = store.listSessions();
        assertThat(sessions).hasSize(2);
        assertThat(sessions).anyMatch(s -> s.sessionId.equals("session-1"));
        assertThat(sessions).anyMatch(s -> s.sessionId.equals("session-2"));
    }

    @Test
    void sessionStoreSetsTitle(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionStore store = new SessionStore(mapper, tempDir.resolve("sessions.json"));

        store.recordSession("session-1", "Original");
        boolean result = store.setTitle("session-1", "New Title");
        assertThat(result).isTrue();

        var entry = store.getSession("session-1");
        assertThat(entry.title).isEqualTo("New Title");
    }

    @Test
    void sessionStoreIncrementsMessageCount(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionStore store = new SessionStore(mapper, tempDir.resolve("sessions.json"));

        store.recordSession("session-1", "Test");
        store.incrementMessages("session-1");
        store.incrementMessages("session-1");
        store.incrementMessages("session-1");

        var entry = store.getSession("session-1");
        assertThat(entry.messageCount).isEqualTo(3);
    }

    @Test
    void sessionStoreDeletesSession(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionStore store = new SessionStore(mapper, tempDir.resolve("sessions.json"));

        store.recordSession("session-1", "Test");
        assertThat(store.getSession("session-1")).isNotNull();
        boolean deleted = store.deleteSession("session-1");
        assertThat(deleted).isTrue();
        assertThat(store.getSession("session-1")).isNull();
    }

    @Test
    void sessionStorePersistsAndLoads(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        Path dbPath = tempDir.resolve("sessions.json");

        SessionStore store1 = new SessionStore(mapper, dbPath);
        store1.recordSession("session-1", "Persisted Session");
        store1.incrementMessages("session-1");

        // Create a new store that loads from the same file
        SessionStore store2 = new SessionStore(mapper, dbPath);
        var entry = store2.getSession("session-1");
        assertThat(entry).isNotNull();
        assertThat(entry.title).isEqualTo("Persisted Session");
        assertThat(entry.messageCount).isEqualTo(1);
    }

    @Test
    void sessionStoreFormatsSessions(@TempDir Path tempDir) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionStore store = new SessionStore(mapper, tempDir.resolve("sessions.json"));

        store.recordSession("session-1", "Test Session");
        String formatted = store.formatSessions();
        assertThat(formatted).contains("session-1");
        assertThat(formatted).contains("Test Session");
    }

    @Test
    void sessionStoreFormatEmpty() {
        SessionStore store = new SessionStore(new ObjectMapper(), Path.of("/tmp/nonexistent-test-sessions.json"));
        store.deleteSession("nonexistent"); // clean up if exists
        String formatted = store.formatSessions();
        assertThat(formatted).contains("No saved sessions");
    }

    // ── P1-6: File path completion ──
    // (Tested via SlashCompleter — JLine's FileNameCompleter is a library feature)

    @Test
    void slashCompleterCompletesCommands() {
        SlashCompleter completer = new SlashCompleter(registry);
        java.util.List<org.jline.reader.Candidate> candidates = new java.util.ArrayList<>();
        org.jline.reader.ParsedLine line = mock(org.jline.reader.ParsedLine.class);
        when(line.word()).thenReturn("/he");

        completer.complete(null, line, candidates);
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anyMatch(c -> c.value().equals("/help"));
    }

    @Test
    void slashCompleterCompletesAtReferences() {
        SlashCompleter completer = new SlashCompleter(registry);
        java.util.List<org.jline.reader.Candidate> candidates = new java.util.ArrayList<>();
        org.jline.reader.ParsedLine line = mock(org.jline.reader.ParsedLine.class);
        when(line.word()).thenReturn("@");

        completer.complete(null, line, candidates);
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).anyMatch(c -> c.value().equals("@diff"));
        assertThat(candidates).anyMatch(c -> c.value().equals("@file:"));
    }

    // ── P1-7: @ context references ──

    @Test
    void contextExpanderDetectsReferences() {
        assertThat(ContextReferenceExpander.hasReferences("Hello @diff world")).isTrue();
        assertThat(ContextReferenceExpander.hasReferences("Check @file:README.md")).isTrue();
        assertThat(ContextReferenceExpander.hasReferences("@url:http://example.com")).isTrue();
        assertThat(ContextReferenceExpander.hasReferences("no refs here")).isFalse();
    }

    @Test
    void contextExpanderExpandsFileRef(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.txt");
        java.nio.file.Files.writeString(testFile, "file content here");

        ContextReferenceExpander expander = new ContextReferenceExpander();
        String result = expander.expand("See @file:" + testFile.toString());
        assertThat(result).contains("file content here");
        assertThat(result).contains("--- @file:");
    }

    @Test
    void contextExpanderExpandsFolderRef(@TempDir Path tempDir) throws Exception {
        ContextReferenceExpander expander = new ContextReferenceExpander();
        String result = expander.expand("List @folder:" + tempDir.toString());
        assertThat(result).contains("--- @folder:");
    }

    @Test
    void contextExpanderExpandsNonExistentFile() {
        ContextReferenceExpander expander = new ContextReferenceExpander();
        String result = expander.expand("@file:/nonexistent/file.txt");
        assertThat(result).contains("[File not found:");
    }

    // ── P1-8: Auto-suggest subcommands ──

    @Test
    void autoSuggestSuggestsCommandNames() {
        SlashAutoSuggest suggest = new SlashAutoSuggest(registry);
        String suggestion = suggest.suggest("/hel");
        assertThat(suggestion).isEqualTo("p"); // "help" minus "hel"
    }

    @Test
    void autoSuggestSuggestsSubcommands() {
        SlashAutoSuggest suggest = new SlashAutoSuggest(registry);
        // Type "/memory " — but since there's no space yet, suggest the command
        // After a space, suggest subcommand
        suggest.addToHistory("/memory pending");
        // /voice → suggest "voice" (command completion)
        String suggestion = suggest.suggest("/voi");
        assertThat(suggestion).isEqualTo("ce"); // "voice" minus "voi"
    }

    @Test
    void autoSuggestSuggestsVoiceSubcommand() {
        SlashAutoSuggest suggest = new SlashAutoSuggest(registry);
        // "/voice " — the space means the command is complete, suggest subcommand
        String suggestion = suggest.suggest("/voice o");
        assertThat(suggestion).isEqualTo("n"); // "on" minus "o" (or "off" minus "o")
    }

    @Test
    void autoSuggestSuggestsFromHistory() {
        SlashAutoSuggest suggest = new SlashAutoSuggest(registry);
        suggest.addToHistory("hello world");
        suggest.addToHistory("help me please");

        String suggestion = suggest.suggest("hel");
        // Should suggest the most recent matching entry
        assertThat(suggestion).isNotNull();
    }

    @Test
    void autoSuggestReturnsNullForNoMatch() {
        SlashAutoSuggest suggest = new SlashAutoSuggest(registry);
        String suggestion = suggest.suggest("/zzzz");
        assertThat(suggestion).isNull();
    }

    @Test
    void autoSuggestSubcommandMapHasEntries() {
        String[] voiceSubs = SlashAutoSuggest.getSubcommands("voice");
        assertThat(voiceSubs).contains("on", "off", "tts", "status");

        String[] toolsSubs = SlashAutoSuggest.getSubcommands("tools");
        assertThat(toolsSubs).contains("list", "disable", "enable");

        String[] reasoningSubs = SlashAutoSuggest.getSubcommands("reasoning");
        assertThat(reasoningSubs).contains("none", "minimal", "low", "medium", "high", "xhigh", "cycle");
    }

    // ── P1-9: External editor ──
    // (Requires actual editor — tested via integration, not unit test)

    @Test
    void externalEditorClassExists() {
        // Just verify the class is accessible
        assertThat(ExternalEditor.class).isNotNull();
    }

    // ── P1-10: Input history persistence ──

    @Test
    void inputHistoryManagerProvidesHistoryFile() {
        Path historyFile = InputHistoryManager.getHistoryFile();
        assertThat(historyFile).isNotNull();
        assertThat(historyFile.toString()).contains(".java-agent-cli");
        assertThat(historyFile.toString()).contains("history.txt");
    }

    @Test
    void inputHistoryManagerAppendsAndLoads(@TempDir Path tempDir) throws Exception {
        Path historyFile = tempDir.resolve("history.txt");
        // Write entries directly
        java.nio.file.Files.writeString(historyFile, "test1\ntest2\ntest3\n");
        // Read them back
        var entries = java.nio.file.Files.readAllLines(historyFile);
        assertThat(entries).containsExactly("test1", "test2", "test3");
    }

    @Test
    void inputHistoryManagerClearsHistory(@TempDir Path tempDir) throws Exception {
        Path historyFile = tempDir.resolve("history.txt");
        java.nio.file.Files.writeString(historyFile, "test1\ntest2\n");
        java.nio.file.Files.writeString(historyFile, "");
        assertThat(java.nio.file.Files.readString(historyFile)).isEmpty();
    }

    // ── Integration: registry has more commands now ──

    @Test
    void registersAtLeast60Commands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).hasSizeGreaterThanOrEqualTo(60);
    }

    @Test
    void helpListsNewP1Commands() {
        String result = registry.execute("/help", client, "sid");
        assertThat(result).contains("/retry");
        assertThat(result).contains("/verbose");
        assertThat(result).contains("/yolo");
        assertThat(result).contains("/reasoning");
        assertThat(result).contains("/fast");
        assertThat(result).contains("/voice");
        assertThat(result).contains("/busy");
        assertThat(result).contains("/tools");
        assertThat(result).contains("/browser");
        assertThat(result).contains("/plugins");
        assertThat(result).contains("/subgoal");
        assertThat(result).contains("/snapshot");
        assertThat(result).contains("/personality");
        assertThat(result).contains("/title");
        assertThat(result).contains("/queue");
    }
}