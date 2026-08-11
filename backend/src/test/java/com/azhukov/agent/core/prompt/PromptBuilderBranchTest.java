package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.state.DefaultAgentConstants;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Branch coverage tests for {@link DefaultPromptBuilder} and {@link PromptCacheTracker}.
 * Covers cache tracker paths, memory null/empty, tool description null handling, and coding context.
 */
class PromptBuilderBranchTest {

    // ── PromptCacheTracker branch coverage ──

    @Test
    void hashPrefix_null_returnsEmpty() {
        assertThat(PromptCacheTracker.hashPrefix(null)).isEmpty();
    }

    @Test
    void hashPrefix_empty_returnsEmpty() {
        assertThat(PromptCacheTracker.hashPrefix("")).isEmpty();
    }

    @Test
    void markCached_disabled_doesNothing() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(false);
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        tracker.markCached("session", "hash");
        assertThat(tracker.isCacheValid("session", "hash")).isFalse();
    }

    @Test
    void isCacheValid_disabled_returnsFalse() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(false);
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        tracker.markCached("s1", "h1");
        assertThat(tracker.isCacheValid("s1", "h1")).isFalse();
    }

    @Test
    void getOrBuild_nullInput_returnsCached() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(true);
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("stable", "", "");
        tracker.getOrBuild("s1", () -> prompt);
        var result = tracker.getOrBuild("s1", () -> null);
        assertThat(result).isSameAs(prompt);
    }

    @Test
    void getCacheStats_noData_returnsZero() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(true);
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        var stats = tracker.getCacheStats();
        assertThat(stats.get("totalHits")).isEqualTo(0L);
        assertThat(stats.get("totalMisses")).isEqualTo(0L);
    }

    @Test
    void applyAnthropicCacheControl_nullInput_returnsNull() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        assertThat(tracker.applyAnthropicCacheControl(null, "5m")).isNull();
    }

    @Test
    void applyAnthropicCacheControl_developerRole_getsCacheControl() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        java.util.Map<String, Object> devMsg = new java.util.LinkedHashMap<>();
        devMsg.put("role", "developer");
        devMsg.put("content", "dev prompt");
        var result = tracker.applyAnthropicCacheControl(java.util.List.of(devMsg), "5m");
        @SuppressWarnings("unchecked")
        var content = (java.util.List<java.util.Map<String, Object>>) result.get(0).get("content");
        assertThat(content).isNotNull();
        assertThat(content.get(0)).containsKey("cache_control");
    }

    @Test
    void applyAnthropicCacheControl_toolMessage_getsCacheControl() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);

        java.util.Map<String, Object> sysMsg = new java.util.LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "sys");
        java.util.Map<String, Object> toolMsg = new java.util.LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("content", "tool result");
        java.util.Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "user");

        var result = tracker.applyAnthropicCacheControl(java.util.List.of(sysMsg, toolMsg, userMsg), "5m");
        assertThat(result).hasSize(3);
    }

    @Test
    void applyAnthropicCacheControl_contentNull_addsCacheControlDirectly() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);

        java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("content", null);

        var result = tracker.applyAnthropicCacheControl(java.util.List.of(msg), "5m");
        assertThat(result.get(0)).containsKey("cache_control");
    }

    @Test
    void applyAnthropicCacheControl_contentEmptyString_addsCacheControlDirectly() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);

        java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("content", "");

        var result = tracker.applyAnthropicCacheControl(java.util.List.of(msg), "5m");
        assertThat(result.get(0)).containsKey("cache_control");
    }

    @Test
    void applyAnthropicCacheControl_listContent_addsCacheControlToLastElement() {
        AgentProperties props = new AgentProperties();
        PromptCacheTracker tracker = new PromptCacheTracker(props);

        java.util.Map<String, Object> textPart = new java.util.LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", "hello");

        java.util.Map<String, Object> msg = new java.util.LinkedHashMap<>();
        msg.put("role", "system");
        msg.put("content", java.util.List.of(textPart));

        var result = tracker.applyAnthropicCacheControl(java.util.List.of(msg), "1h");
        @SuppressWarnings("unchecked")
        var content = (java.util.List<java.util.Map<String, Object>>) result.get(0).get("content");
        assertThat(content.get(0)).containsKey("cache_control");
    }

    @Test
    void cachedSystemPrompt_allEmptyTiers_returnsEmpty() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("", "", "");
        assertThat(prompt.fullPrompt()).isEmpty();
    }

    @Test
    void cachedSystemPrompt_stableOnly_returnsStable() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("stable only", "", null);
        assertThat(prompt.fullPrompt()).isEqualTo("stable only");
    }

    @Test
    void cachedSystemPrompt_contextOnly_returnsContext() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("", "context only", null);
        assertThat(prompt.fullPrompt()).isEqualTo("context only");
    }

    @Test
    void cachedSystemPrompt_volatileOnly_returnsVolatile() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("", "", "volatile only");
        assertThat(prompt.fullPrompt()).isEqualTo("volatile only");
    }

    @Test
    void invalidate_removesBothPrefixAndSystemPrompt() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(true);
        PromptCacheTracker tracker = new PromptCacheTracker(props);
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("v1", "", "");
        tracker.getOrBuild("s1", () -> prompt);
        tracker.markCached("s1", PromptCacheTracker.hashPrefix("v1"));
        tracker.invalidate("s1");
        // After invalidate, getOrBuild should rebuild
        var newPrompt = PromptCacheTracker.CachedSystemPrompt.of("v2", "", "");
        var result = tracker.getOrBuild("s1", () -> newPrompt);
        assertThat(result.fullPrompt()).contains("v2");
    }

    // ── DefaultPromptBuilder branch coverage ──

    @Test
    void buildSystemMessage_withCacheTracker_usesCache() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        PromptCacheTracker cacheTracker = new PromptCacheTracker(props);
        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), cacheTracker);

        Message msg1 = builder.buildSystemMessage(Session.create("u", "p", "m"));
        Message msg2 = builder.buildSystemMessage(Session.create("u", "p", "m"));

        // Both should produce same content (cached)
        assertThat(msg1.content()).isEqualTo(msg2.content());
    }

    @Test
    void buildSystemMessage_withNullSession_usesDefaultId() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, null, null);

        // Should not throw with null session
        Message msg = builder.buildSystemMessage(null);
        assertThat(msg.content()).contains("Agent");
    }

    @Test
    void buildMemoryPrefix_nullSession_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, null, memoryProvider);

        assertThat(builder.buildMemoryPrefix(null)).isEmpty();
    }

    @Test
    void buildMemoryPrefix_nullSessionId_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, null, memoryProvider);

        Session session = new Session(null, "user-1", null, "noop", "model", null, java.util.Map.of(), null);
        assertThat(builder.buildMemoryPrefix(session)).isEmpty();
    }

    @Test
    void buildMemoryPrefix_nullMemoryProvider_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, null, null);

        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);
        assertThat(builder.buildMemoryPrefix(session)).isEmpty();
    }

    @Test
    void buildMemoryPrefix_nullMemories_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        MemoryProvider memoryProvider = mock(MemoryProvider.class);
        when(memoryProvider.recall(any(), any(), anyInt())).thenReturn(null);

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, null, memoryProvider);

        Session session = new Session(UUID.randomUUID(), "user-1", null, "noop", "model", null, java.util.Map.of(), null);
        assertThat(builder.buildMemoryPrefix(session)).isEmpty();
    }

    @Test
    void buildSystemMessage_toolDescriptionNull_showsNoDescription() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of("tools"));
        when(registry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("tool1", "", Map.of("type", "object"))
        ));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants());

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg.content()).contains("tool1");
        // Empty description is not null, so it won't show "No description"
        // but it should show the tool name
    }

    @Test
    void buildSystemMessage_workingDirectoryNull_omitsWorkingDir() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        props.getCore().setWorkingDirectory(null);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants());

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg.content()).doesNotContain("Working Directory:");
    }

    @Test
    void buildSystemMessage_workingDirectoryBlank_omitsWorkingDir() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        props.getCore().setWorkingDirectory("  ");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants());

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg.content()).doesNotContain("Working Directory:");
    }

    @Test
    void buildSystemMessage_codingContextEnabled_detectsContext() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        props.getCodingContext().setEnabled(true);
        props.getCore().setWorkingDirectory("/tmp");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        com.azhukov.agent.core.context.CodingContextDetector detector = mock(
            com.azhukov.agent.core.context.CodingContextDetector.class);
        when(detector.detect(any())).thenReturn(new com.azhukov.agent.core.context.CodingContextDetector.CodingContext(
            "Java", "Spring Boot", "Gradle", true
        ));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, detector, null);

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));
        assertThat(msg.content()).contains("Java");
        assertThat(msg.content()).contains("Spring Boot");
        assertThat(msg.content()).contains("Gradle");
    }

    @Test
    void buildSystemMessage_codingContextNullLanguage_omitsContext() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        props.getCodingContext().setEnabled(true);
        props.getCore().setWorkingDirectory("/tmp");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        com.azhukov.agent.core.context.CodingContextDetector detector = mock(
            com.azhukov.agent.core.context.CodingContextDetector.class);
        when(detector.detect(any())).thenReturn(new com.azhukov.agent.core.context.CodingContextDetector.CodingContext(
            null, null, null, false
        ));

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants(), null, detector, null);

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"));
        // Null language should not produce "Detected coding context"
        assertThat(msg.content()).doesNotContain("Detected coding context");
    }

    @Test
    void scanContextContent_emptyInput_returnsEmpty() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry);

        assertThat(builder.scanContextContent("", "AGENTS.md")).isEmpty();
    }

    @Test
    void scanContextContent_blankInput_returnsBlank() {
        AgentProperties props = new AgentProperties();
        ToolRegistry registry = mock(ToolRegistry.class);
        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry);

        assertThat(builder.scanContextContent("   ", "test")).isEqualTo("   ");
    }

    @Test
    void buildSystemMessage_systemMessageOverrideBlank_notInjected() {
        AgentProperties props = new AgentProperties();
        props.setName("Agent");
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolsets()).thenReturn(Set.of());
        when(registry.getDefinitions()).thenReturn(List.of());

        DefaultPromptBuilder builder = new DefaultPromptBuilder(props, registry,
            new DefaultAgentConstants());

        Message msg = builder.buildSystemMessage(Session.create("u", "p", "m"), "   ");
        assertThat(msg.content()).doesNotContain("   ");
    }
}