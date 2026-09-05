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

    // ── /export ──

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
        // f10b: /image now really attaches the pending reference for the next prompt
        assertThat(result).contains("Image attached");
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
    void allNewCommandsAreRegistered() {
        List<String> names = registry.getCommandNames();
        assertThat(names).contains(
            "profile", "toolsets", "debug", "plan", "export",
            "handoff", "redraw", "image", "whoami", "statusbar", "gquota",
            "platforms", "editor"
        );
    }

    @Test
    void registersAtLeast79Commands() {
        List<String> names = registry.getCommandNames();
        assertThat(names).hasSizeGreaterThanOrEqualTo(79);
    }
}