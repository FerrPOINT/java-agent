package com.azhukov.agent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentPropertiesFullCoverageTest {

    @Test
    @DisplayName("AuxiliaryProperties: all getters/setters")
    void auxiliaryPropertiesFullCoverage() {
        var p = new AgentProperties.AuxiliaryProperties();
        p.setEnabled(true);
        p.setProvider("ollama");
        p.setBaseUrl("http://localhost:11434");
        p.setApiKey("key123");
        p.setModelName("llama3");
        p.setTimeoutSeconds(120);
        p.setMaxRetries(5);

        assertTrue(p.isEnabled());
        assertEquals("ollama", p.getProvider());
        assertEquals("http://localhost:11434", p.getBaseUrl());
        assertEquals("key123", p.getApiKey());
        assertEquals("llama3", p.getModelName());
        assertEquals(120, p.getTimeoutSeconds());
        assertEquals(5, p.getMaxRetries());
    }

    @Test
    @DisplayName("VisionProperties: all getters/setters")
    void visionPropertiesFullCoverage() {
        var p = new AgentProperties.VisionProperties();
        p.setProvider("openai");
        p.setBaseUrl("http://vision.api");
        p.setApiKey("vkey");
        p.setModelName("gpt-4o");
        p.setTimeoutSeconds(60);
        p.setMaxRetries(2);
        p.setUseAuxiliaryFirst(false);

        assertEquals("openai", p.getProvider());
        assertEquals("http://vision.api", p.getBaseUrl());
        assertEquals("vkey", p.getApiKey());
        assertEquals("gpt-4o", p.getModelName());
        assertEquals(60, p.getTimeoutSeconds());
        assertEquals(2, p.getMaxRetries());
        assertFalse(p.isUseAuxiliaryFirst());
    }

    @Test
    @DisplayName("BrowserProperties: all getters/setters")
    void browserPropertiesFullCoverage() {
        var p = new AgentProperties.BrowserProperties();
        p.setCdpUrl("http://localhost:9223");
        p.setDefaultTimeoutMs(30000);
        p.setPageLoadTimeoutMs(60000);
        p.setMaxTabs(10);
        p.setHeadless(false);
        p.setExecutablePath("/usr/bin/chromium");

        assertEquals("http://localhost:9223", p.getCdpUrl());
        assertEquals(30000, p.getDefaultTimeoutMs());
        assertEquals(60000, p.getPageLoadTimeoutMs());
        assertEquals(10, p.getMaxTabs());
        assertFalse(p.isHeadless());
        assertEquals("/usr/bin/chromium", p.getExecutablePath());
    }

    @Test
    @DisplayName("ChromiumProperties: all getters/setters")
    void chromiumPropertiesFullCoverage() {
        var p = new AgentProperties.ChromiumProperties();
        p.setAutoStart(false);
        p.setAutoInstall(false);
        p.setDownloadUrl("https://custom.url");
        p.setRevision("r123");
        p.setLaunchTimeoutSeconds(60);
        p.setHeadless(false);
        p.setExecutablePath("/path/to/chrome");
        p.setUserDataDir("/tmp/chrome-data");
        p.getExtraArgs().add("--no-sandbox");

        assertFalse(p.isAutoStart());
        assertFalse(p.isAutoInstall());
        assertEquals("https://custom.url", p.getDownloadUrl());
        assertEquals("r123", p.getRevision());
        assertEquals(60, p.getLaunchTimeoutSeconds());
        assertFalse(p.isHeadless());
        assertEquals("/path/to/chrome", p.getExecutablePath());
        assertEquals("/tmp/chrome-data", p.getUserDataDir());
        assertEquals(1, p.getExtraArgs().size());
        assertEquals("--no-sandbox", p.getExtraArgs().get(0));
    }

    @Test
    @DisplayName("WebProperties: all getters/setters")
    void webPropertiesFullCoverage() {
        var p = new AgentProperties.WebProperties();
        p.setSearchResults(10);
        p.setExtractTimeoutSeconds(60);
        p.setExtractMaxChars(50000);
        p.setSearchProvider("google");
        p.getAllowedDomains().add("example.com");
        p.getBlockedDomains().add("bad.com");

        assertEquals(10, p.getSearchResults());
        assertEquals(60, p.getExtractTimeoutSeconds());
        assertEquals(50000, p.getExtractMaxChars());
        assertEquals("google", p.getSearchProvider());
        assertEquals(1, p.getAllowedDomains().size());
        assertEquals(1, p.getBlockedDomains().size());
    }

    @Test
    @DisplayName("TerminalProperties: all getters/setters")
    void terminalPropertiesFullCoverage() {
        var p = new AgentProperties.TerminalProperties();
        p.setDefaultTimeoutSeconds(120);
        p.setMaxTimeoutSeconds(3600);
        p.setDockerEnabled(true);
        p.getBlockedCommands().add("rm -rf");
        p.getRequireApprovalCommands().add("sudo");

        assertEquals(120, p.getDefaultTimeoutSeconds());
        assertEquals(3600, p.getMaxTimeoutSeconds());
        assertTrue(p.isDockerEnabled());
        assertEquals(1, p.getBlockedCommands().size());
        assertEquals(1, p.getRequireApprovalCommands().size());
    }

    @Test
    @DisplayName("FileProperties: all getters/setters")
    void filePropertiesFullCoverage() {
        var p = new AgentProperties.FileProperties();
        p.setReadMaxChars(50000);
        p.setWriteMaxChars(50000);
        p.getAllowedPaths().add("/tmp");
        p.getBlockedPaths().add("/etc");
        p.getBlockedExtensions().add(".exe");

        assertEquals(50000, p.getReadMaxChars());
        assertEquals(50000, p.getWriteMaxChars());
        assertEquals(1, p.getAllowedPaths().size());
        assertEquals(1, p.getBlockedPaths().size());
        assertEquals(1, p.getBlockedExtensions().size());
    }

    @Test
    @DisplayName("MemoryProperties: all getters/setters")
    void memoryPropertiesFullCoverage() {
        var p = new AgentProperties.MemoryProperties();
        p.setMaxFactsPerUser(500);
        p.setMaxFactsPerQuery(20);
        p.setSimilarityThreshold(0.8);

        assertEquals(500, p.getMaxFactsPerUser());
        assertEquals(20, p.getMaxFactsPerQuery());
        assertEquals(0.8, p.getSimilarityThreshold());
    }

    @Test
    @DisplayName("SkillsProperties: all getters/setters")
    void skillsPropertiesFullCoverage() {
        var p = new AgentProperties.SkillsProperties();
        p.setEnabled(false);
        p.setMaxSkillsInPrompt(5);
        p.setMaxCharsPerSkill(2000);
        p.getDefaultToolsets().add("custom");

        assertFalse(p.isEnabled());
        assertEquals(5, p.getMaxSkillsInPrompt());
        assertEquals(2000, p.getMaxCharsPerSkill());
        assertTrue(p.getDefaultToolsets().contains("custom"));
    }

    @Test
    @DisplayName("SessionSearchProperties: all getters/setters")
    void sessionSearchPropertiesFullCoverage() {
        var p = new AgentProperties.SessionSearchProperties();
        p.setMaxResults(20);
        p.setSnippetChars(500);

        assertEquals(20, p.getMaxResults());
        assertEquals(500, p.getSnippetChars());
    }

    @Test
    @DisplayName("ToolOutputProperties: all getters/setters")
    void toolOutputPropertiesFullCoverage() {
        var p = new AgentProperties.ToolOutputProperties();
        p.setMaxChars(20000);
        p.setTruncateWarningChars(15000);
        p.setTimeoutSeconds(120);
        p.setIncludeTimestamps(false);

        assertEquals(20000, p.getMaxChars());
        assertEquals(15000, p.getTruncateWarningChars());
        assertEquals(120, p.getTimeoutSeconds());
        assertFalse(p.isIncludeTimestamps());
        assertEquals(120, p.getTimeoutSecondsOrDefault(60));
        // Test fallback
        p.setTimeoutSeconds(0);
        assertEquals(60, p.getTimeoutSecondsOrDefault(60));
    }

    @Test
    @DisplayName("ContextProperties: all getters/setters")
    void contextPropertiesFullCoverage() {
        var p = new AgentProperties.ContextProperties();
        p.setMaxTokens(32000);
        p.setTargetTokens(24000);
        p.setSummaryChunkTokens(4000);
        p.setMaxContextMessages(100);

        assertEquals(32000, p.getMaxTokens());
        assertEquals(24000, p.getTargetTokens());
        assertEquals(4000, p.getSummaryChunkTokens());
        assertEquals(100, p.getMaxContextMessages());
    }

    @Test
    @DisplayName("DelegationProperties: all getters/setters")
    void delegationPropertiesFullCoverage() {
        var p = new AgentProperties.DelegationProperties();
        p.setEnabled(false);
        p.setMaxDepth(5);
        p.setDefaultTimeoutSeconds(600);

        assertFalse(p.isEnabled());
        assertEquals(5, p.getMaxDepth());
        assertEquals(600, p.getDefaultTimeoutSeconds());
    }

    @Test
    @DisplayName("McpProperties: all getters/setters including ServerProperties")
    void mcpPropertiesFullCoverage() {
        var p = new AgentProperties.McpProperties();
        p.setEnabled(true);

        var server = new AgentProperties.McpProperties.ServerProperties();
        server.setName("echo-server");
        server.setTransport("stdio");
        server.setCommand("echo");
        server.getArgs().add("--help");
        server.getEnv().put("KEY", "VALUE");
        server.setBaseUrl("http://localhost:3000");
        server.setTimeoutSeconds(15);

        p.getServers().add(server);

        assertTrue(p.isEnabled());
        assertEquals(1, p.getServers().size());
        var s = p.getServers().get(0);
        assertEquals("echo-server", s.getName());
        assertEquals("stdio", s.getTransport());
        assertEquals("echo", s.getCommand());
        assertEquals(1, s.getArgs().size());
        assertEquals("VALUE", s.getEnv().get("KEY"));
        assertEquals("http://localhost:3000", s.getBaseUrl());
        assertEquals(15, s.getTimeoutSeconds());
    }

    @Test
    @DisplayName("SecurityProperties: all getters/setters")
    void securityPropertiesFullCoverage() {
        var p = new AgentProperties.SecurityProperties();
        p.setApprovalsEnabled(false);
        p.setFileSafetyEnabled(false);
        p.setUrlSafetyEnabled(false);
        p.setRedactEnabled(false);
        p.setAlwaysRequireApprovalTools(List.of("terminal"));
        p.setAllowedPaths(List.of("/tmp"));
        p.setBlockedCommands(List.of("rm -rf"));
        p.setBlockedUrlHosts(List.of("evil.com"));
        p.setSecretPatterns(List.of("API_KEY"));

        assertFalse(p.isApprovalsEnabled());
        assertFalse(p.isFileSafetyEnabled());
        assertFalse(p.isUrlSafetyEnabled());
        assertFalse(p.isRedactEnabled());
        assertEquals(1, p.getAlwaysRequireApprovalTools().size());
        assertEquals(1, p.getAllowedPaths().size());
        assertEquals(1, p.getBlockedCommands().size());
        assertEquals(1, p.getBlockedUrlHosts().size());
        assertEquals(1, p.getSecretPatterns().size());
    }

    @Test
    @DisplayName("TelegramProperties: all getters/setters")
    void telegramPropertiesFullCoverage() {
        var p = new AgentProperties.TelegramProperties();
        p.setBotToken("123:ABC");
        p.setWebhookUrl("https://hook.url");
        p.setTimeoutSeconds(60);
        p.getAllowedUserIds().add("12345");
        p.getAllowedUsernames().add("user1");
        p.setAllowByDefault(true);

        assertEquals("123:ABC", p.getBotToken());
        assertEquals("https://hook.url", p.getWebhookUrl());
        assertEquals(60, p.getTimeoutSeconds());
        assertEquals(1, p.getAllowedUserIds().size());
        assertEquals(1, p.getAllowedUsernames().size());
        assertTrue(p.isAllowByDefault());
    }

    @Test
    @DisplayName("CoreProperties: all getters/setters")
    void corePropertiesFullCoverage() {
        var p = new AgentProperties.CoreProperties();
        p.setMaxTurns(50);
        p.setToolUseEnforcement("strict");
        p.setTaskCompletionGuidance(false);
        p.setParallelToolCallGuidance(false);
        p.setAutoTitleSession(false);
        p.setReasoningConfig("high");
        p.setDefaultSystemPrompt("Custom prompt");
        p.setHttpClientTimeoutSeconds(60);
        p.setMaxReferenceFileBytes(50000);
        p.setWorkingDirectory("/custom/dir");
        p.setHttpUserAgent("CustomAgent/2.0");

        assertEquals(50, p.getMaxTurns());
        assertEquals("strict", p.getToolUseEnforcement());
        assertFalse(p.isTaskCompletionGuidance());
        assertFalse(p.isParallelToolCallGuidance());
        assertFalse(p.isAutoTitleSession());
        assertEquals("high", p.getReasoningConfig());
        assertEquals("Custom prompt", p.getDefaultSystemPrompt());
        assertEquals(60, p.getHttpClientTimeoutSeconds());
        assertEquals(50000, p.getMaxReferenceFileBytes());
        assertEquals("/custom/dir", p.getWorkingDirectory());
        assertEquals("CustomAgent/2.0", p.getHttpUserAgent());
        assertEquals(64000, p.getMaxTotalChars());
    }

    @Test
    @DisplayName("BudgetProperties: all getters/setters")
    void budgetPropertiesFullCoverage() {
        var p = new AgentProperties.BudgetProperties();
        p.setMaxModelCallsPerTurn(10);
        p.setMaxToolExecutionsPerTurn(50);
        p.setMaxTokensPerTurn(500000);
        p.setMaxToolDurationMsPerTurn(1200000);
        p.setEnabled(false);

        assertEquals(10, p.getMaxModelCallsPerTurn());
        assertEquals(50, p.getMaxToolExecutionsPerTurn());
        assertEquals(500000, p.getMaxTokensPerTurn());
        assertEquals(1200000, p.getMaxToolDurationMsPerTurn());
        assertFalse(p.isEnabled());
    }

    @Test
    @DisplayName("ModelProperties: all getters/setters including headers")
    void modelPropertiesFullCoverage() {
        var p = new AgentProperties.ModelProperties();
        p.setProvider("anthropic");
        p.setBaseUrl("https://api.anthropic.com");
        p.setApiKey("sk-key");
        p.setModelName("claude-4");
        p.setTimeoutSeconds(300);
        p.setMaxRetries(5);
        p.setMaxTokens(8192);
        p.setTemperature(0.3);
        p.getHeaders().put("X-Custom", "value");

        assertEquals("anthropic", p.getProvider());
        assertEquals("https://api.anthropic.com", p.getBaseUrl());
        assertEquals("sk-key", p.getApiKey());
        assertEquals("claude-4", p.getModelName());
        assertEquals(300, p.getTimeoutSeconds());
        assertEquals(5, p.getMaxRetries());
        assertEquals(8192, p.getMaxTokens());
        assertEquals(0.3, p.getTemperature());
        assertEquals("value", p.getHeaders().get("X-Custom"));
    }

    @Test
    @DisplayName("AgentProperties: name getter/setter")
    void agentPropertiesNameCoverage() {
        AgentProperties p = new AgentProperties();
        p.setName("Custom Agent");
        assertEquals("Custom Agent", p.getName());
        assertEquals("Джава агент", new AgentProperties().getName());
    }
}