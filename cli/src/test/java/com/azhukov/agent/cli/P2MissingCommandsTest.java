package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * P2-11: Tests for the newly added missing CLI commands.
 */
class P2MissingCommandsTest {

    private SlashCommandRegistry registry;
    private BackendClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
    }

    // ── /profile ──

    @Test
    void profileCommandShowsProfileAndHomeDir() {
        String result = registry.execute("/profile", client, "sid");
        assertThat(result).contains("Active profile: default");
        assertThat(result).contains("Home directory:");
    }

    @Test
    void profileCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("profile");
    }

    // ── /toolsets ──

    @Test
    void toolsetsCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("toolsets");
    }

    @Test
    void toolsetsListCallsBackend() {
        ArrayNode arr = mapper.createArrayNode();
        arr.add("filesystem");
        arr.add("web-search");
        when(client.listToolsets()).thenReturn(arr);
        when(client.prettyPrint(any(JsonNode.class))).thenAnswer(inv -> 
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inv.getArgument(0)));
        String result = registry.execute("/toolsets", client, "sid");
        assertThat(result).contains("filesystem");
        assertThat(result).contains("web-search");
    }

    @Test
    void toolsetsEnableCallsBackend() {
        when(client.toggleToolset("filesystem", true)).thenReturn("Toolset filesystem: enabled");
        String result = registry.execute("/toolsets enable filesystem", client, "sid");
        assertThat(result).contains("enabled");
    }

    @Test
    void toolsetsDisableCallsBackend() {
        when(client.toggleToolset("web-search", false)).thenReturn("Toolset web-search: disabled");
        String result = registry.execute("/toolsets disable web-search", client, "sid");
        assertThat(result).contains("disabled");
    }

    @Test
    void toolsetsEnableWithoutNameShowsUsage() {
        String result = registry.execute("/toolsets enable", client, "sid");
        assertThat(result).contains("Usage: /toolsets enable");
    }

    @Test
    void toolsetsInvalidSubcommandShowsUsage() {
        String result = registry.execute("/toolsets bogus", client, "sid");
        assertThat(result).contains("Usage: /toolsets");
    }

    // ── /debug ──

    @Test
    void debugCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("debug");
    }

    @Test
    void debugToggleOnOff() {
        String result1 = registry.execute("/debug on", client, "sid");
        assertThat(result1).contains("Debug mode: ON");
        assertThat(registry.getCliState().isDebugMode()).isTrue();

        String result2 = registry.execute("/debug off", client, "sid");
        assertThat(result2).contains("Debug mode: OFF");
        assertThat(registry.getCliState().isDebugMode()).isFalse();
    }

    @Test
    void debugToggleCycles() {
        // Start with off (default)
        assertThat(registry.getCliState().isDebugMode()).isFalse();
        String result = registry.execute("/debug", client, "sid");
        assertThat(result).contains("Debug mode: ON");
        assertThat(registry.getCliState().isDebugMode()).isTrue();

        result = registry.execute("/debug", client, "sid");
        assertThat(result).contains("Debug mode: OFF");
        assertThat(registry.getCliState().isDebugMode()).isFalse();
    }

    @Test
    void debugReportCallsBackend() {
        when(client.uploadDebugReport()).thenReturn("Debug report uploaded. Shareable link: https://example.com/r/abc");
        String result = registry.execute("/debug report", client, "sid");
        assertThat(result).contains("Debug report uploaded");
        assertThat(result).contains("https://example.com/r/abc");
    }

    @Test
    void debugInvalidArgShowsUsage() {
        String result = registry.execute("/debug bogus", client, "sid");
        assertThat(result).contains("Usage: /debug");
    }

    // ── /plan ──

    @Test
    void planCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("plan");
    }

    @Test
    void planCommandCallsBackend() {
        when(client.getPlan("sid")).thenReturn("Current plan:\n  1. [ ] Build feature\n  2. [x] Write tests");
        String result = registry.execute("/plan", client, "sid");
        assertThat(result).contains("Current plan");
        assertThat(result).contains("Build feature");
        assertThat(result).contains("Write tests");
    }

    @Test
    void planCommandNoPlan() {
        when(client.getPlan("sid")).thenReturn("No plan set for this session.");
        String result = registry.execute("/plan", client, "sid");
        assertThat(result).contains("No plan set");
    }

    // ── /export ──

    @Test
    void exportCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("export");
    }

    @Test
    void exportCommandReturnsSessionData() {
        when(client.exportSession("sid")).thenReturn("{\"session\":\"sid\",\"messages\":[]}");
        String result = registry.execute("/export", client, "sid");
        assertThat(result).contains("session");
        assertThat(result).contains("sid");
    }

    @Test
    void exportCommandWritesToFile() throws Exception {
        when(client.exportSession("sid")).thenReturn("{\"session\":\"sid\"}");
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-export-", ".json");
        tempFile.toFile().deleteOnExit();
        String result = registry.execute("/export " + tempFile.toString(), client, "sid");
        assertThat(result).contains("Session exported to:");
        String content = java.nio.file.Files.readString(tempFile);
        assertThat(content).contains("session");
    }

    // ── /import ──

    @Test
    void importCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("import");
    }

    @Test
    void importCommandNoArgsShowsUsage() {
        String result = registry.execute("/import", client, "sid");
        assertThat(result).contains("Usage: /import");
    }

    @Test
    void importCommandWithInlineJsonCallsBackend() {
        when(client.importSession("{\"session\":\"test\"}")).thenReturn("Session imported.");
        String result = registry.execute("/import {\"session\":\"test\"}", client, "sid");
        assertThat(result).contains("Session imported");
    }

    @Test
    void importCommandWithFileCallsBackend() throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-import-", ".json");
        tempFile.toFile().deleteOnExit();
        java.nio.file.Files.writeString(tempFile, "{\"session\":\"from-file\"}");
        when(client.importSession("{\"session\":\"from-file\"}")).thenReturn("Session imported.");
        String result = registry.execute("/import " + tempFile.toString(), client, "sid");
        assertThat(result).contains("Session imported");
    }

    // ── /sweep ──

    @Test
    void sweepCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("sweep");
    }

    @Test
    void sweepCommandCallsBackendWithDefaultDays() {
        when(client.sweepSessions("default", 30)).thenReturn("Sweep complete. Removed 5 old sessions.");
        String result = registry.execute("/sweep", client, "sid");
        assertThat(result).contains("Sweep complete");
    }

    @Test
    void sweepCommandWithCustomDays() {
        when(client.sweepSessions("default", 7)).thenReturn("Sweep complete. Removed 2 old sessions.");
        String result = registry.execute("/sweep 7", client, "sid");
        assertThat(result).contains("Sweep complete");
    }

    @Test
    void sweepCommandInvalidDaysShowsError() {
        String result = registry.execute("/sweep abc", client, "sid");
        assertThat(result).contains("Invalid number of days");
    }

    // ── /handoff ──

    @Test
    void handoffCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("handoff");
    }

    @Test
    void handoffCommandNoArgsShowsUsage() {
        String result = registry.execute("/handoff", client, "sid");
        assertThat(result).contains("Usage: /handoff");
    }

    @Test
    void handoffCommandWithModelCallsBackend() {
        when(client.handoffModel("sid", "gpt-4o", null)).thenReturn("Handoff: Model switched to: gpt-4o");
        String result = registry.execute("/handoff gpt-4o", client, "sid");
        assertThat(result).contains("Handoff");
        assertThat(result).contains("gpt-4o");
    }

    @Test
    void handoffCommandWithModelAndProviderCallsBackend() {
        when(client.handoffModel("sid", "gpt-4o", "openai")).thenReturn("Handoff: Model switched to: gpt-4o (provider: openai)");
        String result = registry.execute("/handoff gpt-4o openai", client, "sid");
        assertThat(result).contains("Handoff");
        assertThat(result).contains("openai");
    }

    // ── /suggestions ──

    @Test
    void suggestionsCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("suggestions");
    }

    @Test
    void suggestionsListCallsBackend() {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode s1 = mapper.createObjectNode();
        s1.put("id", "sugg-1");
        s1.put("text", "Automate daily summary");
        arr.add(s1);
        when(client.getSuggestions()).thenReturn(arr);
        when(client.prettyPrint(any(JsonNode.class))).thenAnswer(inv -> 
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inv.getArgument(0)));
        String result = registry.execute("/suggestions", client, "sid");
        assertThat(result).contains("Automate daily summary");
    }

    @Test
    void suggestionsDismissCallsBackend() {
        when(client.dismissSuggestion("sugg-1")).thenReturn("Suggestion dismissed: sugg-1");
        String result = registry.execute("/suggestions dismiss sugg-1", client, "sid");
        assertThat(result).contains("dismissed");
    }

    @Test
    void suggestionsDismissWithoutIdShowsUsage() {
        String result = registry.execute("/suggestions dismiss", client, "sid");
        assertThat(result).contains("Usage: /suggestions dismiss");
    }

    @Test
    void suggestionsAliasWorks() {
        ArrayNode arr = mapper.createArrayNode();
        when(client.getSuggestions()).thenReturn(arr);
        when(client.prettyPrint(any(JsonNode.class))).thenAnswer(inv -> 
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inv.getArgument(0)));
        String result = registry.execute("/suggest", client, "sid");
        // Should resolve via alias
        assertThat(result).isNotNull();
    }

    // ── /annotate ──

    @Test
    void annotateCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("annotate");
    }

    @Test
    void annotateCommandNoArgsShowsUsage() {
        String result = registry.execute("/annotate", client, "sid");
        assertThat(result).contains("Usage: /annotate");
    }

    @Test
    void annotateCommandWithNoteCallsBackend() {
        when(client.annotateSession("sid", "This is a note")).thenReturn("Annotation saved.");
        String result = registry.execute("/annotate This is a note", client, "sid");
        assertThat(result).contains("Annotation saved");
    }

    // ── /replay ──

    @Test
    void replayCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("replay");
    }

    @Test
    void replayCommandCallsBackend() {
        when(client.replaySession("sid", null)).thenReturn("Replay started.");
        String result = registry.execute("/replay", client, "sid");
        assertThat(result).contains("Replay started");
    }

    @Test
    void replayCommandWithFromPointCallsBackend() {
        when(client.replaySession("sid", "msg-5")).thenReturn("Replay started from msg-5.");
        String result = registry.execute("/replay msg-5", client, "sid");
        assertThat(result).contains("Replay started");
    }

    // ── /redraw ──

    @Test
    void redrawCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("redraw");
    }

    @Test
    void redrawCommandReturnsMessage() {
        String result = registry.execute("/redraw", client, "sid");
        assertThat(result).contains("Screen redrawn");
    }

    // ── /image ──

    @Test
    void imageCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("image");
    }

    @Test
    void imageCommandNoArgsShowsUsage() {
        String result = registry.execute("/image", client, "sid");
        assertThat(result).contains("Usage: /image");
    }

    @Test
    void imageCommandWithNonexistentFileShowsError() {
        String result = registry.execute("/image /nonexistent/path/to/file.png", client, "sid");
        assertThat(result).contains("File not found");
    }

    @Test
    void imageCommandWithExistingFileReturnsMessage() throws Exception {
        java.nio.file.Path tempImg = java.nio.file.Files.createTempFile("test-img-", ".png");
        tempImg.toFile().deleteOnExit();
        String result = registry.execute("/image " + tempImg.toString(), client, "sid");
        assertThat(result).contains("not yet supported");
    }

    // ── /whoami ──

    @Test
    void whoamiCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("whoami");
    }

    @Test
    void whoamiCommandReturnsUserInfo() {
        String result = registry.execute("/whoami", client, "sid");
        assertThat(result).contains("User: default");
        assertThat(result).contains("Profile: default");
        assertThat(result).contains("Access: user");
    }

    // ── /statusbar ──

    @Test
    void statusbarCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("statusbar");
    }

    @Test
    void statusbarCommandReturnsMessage() {
        String result = registry.execute("/statusbar", client, "sid");
        assertThat(result).contains("Status bar");
    }

    @Test
    void statusbarAliasWorks() {
        assertThat(registry.resolveCommand("sb")).isEqualTo("statusbar");
    }

    // ── /gquota ──

    @Test
    void gquotaCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("gquota");
    }

    @Test
    void gquotaCommandReturnsData() {
        ObjectNode insights = mapper.createObjectNode();
        insights.put("quotaUsed", 1000);
        insights.put("quotaTotal", 10000);
        when(client.getInsights()).thenReturn(insights);
        when(client.prettyPrint(any(JsonNode.class))).thenAnswer(inv -> 
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inv.getArgument(0)));
        String result = registry.execute("/gquota", client, "sid");
        assertThat(result).contains("Gemini quota");
    }

    // ── /platforms ──

    @Test
    void platformsCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("platforms");
    }

    @Test
    void platformsCommandReturnsStatus() {
        ArrayNode plugins = mapper.createArrayNode();
        when(client.listPlugins()).thenReturn(plugins);
        when(client.prettyPrint(any(JsonNode.class))).thenAnswer(inv -> 
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(inv.getArgument(0)));
        String result = registry.execute("/platforms", client, "sid");
        assertThat(result).contains("Platform status");
    }

    // ── /editor ──

    @Test
    void editorCommandIsRegistered() {
        assertThat(registry.getCommandNames()).contains("editor");
    }

    // ── Aliases ──

    @Test
    void versionAliasVWorks() {
        assertThat(registry.resolveCommand("v")).isEqualTo("version");
    }

    // ── Command count ──

    @Test
    void registersAtLeast85Commands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).hasSizeGreaterThanOrEqualTo(85);
    }

    @Test
    void allNewCommandsAreRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains(
            "profile", "toolsets", "debug", "plan", "export", "import",
            "sweep", "handoff", "suggestions", "annotate", "replay",
            "redraw", "image", "whoami", "statusbar", "gquota",
            "platforms", "editor"
        );
    }
}