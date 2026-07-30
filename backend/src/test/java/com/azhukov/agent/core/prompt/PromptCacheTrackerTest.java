package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCacheTrackerTest {

    private AgentProperties properties;
    private PromptCacheTracker tracker;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getPromptCaching().setEnabled(true);
        tracker = new PromptCacheTracker(properties);
    }

    @Test
    void markCached_andIsCacheValid_hit() {
        String sessionId = "session-1";
        String hash = PromptCacheTracker.hashPrefix("system prompt content");
        tracker.markCached(sessionId, hash);
        assertThat(tracker.isCacheValid(sessionId, hash)).isTrue();
    }

    @Test
    void isCacheValid_miss_whenHashChanged() {
        String sessionId = "session-2";
        tracker.markCached(sessionId, PromptCacheTracker.hashPrefix("old"));
        assertThat(tracker.isCacheValid(sessionId, PromptCacheTracker.hashPrefix("new"))).isFalse();
    }

    @Test
    void isCacheValid_miss_whenNotCached() {
        assertThat(tracker.isCacheValid("never-seen", "anyhash")).isFalse();
    }

    @Test
    void invalidate_removesEntry() {
        String sessionId = "session-3";
        tracker.markCached(sessionId, PromptCacheTracker.hashPrefix("content"));
        tracker.invalidate(sessionId);
        assertThat(tracker.isCacheValid(sessionId, PromptCacheTracker.hashPrefix("content"))).isFalse();
    }

    @Test
    void getOrBuild_cachesAndReturns() {
        String sessionId = "session-4";
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("stable", "context", "volatile");
        var result1 = tracker.getOrBuild(sessionId, () -> prompt);
        var result2 = tracker.getOrBuild(sessionId, () -> PromptCacheTracker.CachedSystemPrompt.of("different", "", ""));
        assertThat(result2).isSameAs(result1);
        assertThat(result2.fullPrompt()).contains("stable");
    }

    @Test
    void invalidateSystemPrompt_forcesRebuild() {
        String sessionId = "session-5";
        var prompt1 = PromptCacheTracker.CachedSystemPrompt.of("v1", "", "");
        var result1 = tracker.getOrBuild(sessionId, () -> prompt1);
        tracker.invalidateSystemPrompt(sessionId);
        var prompt2 = PromptCacheTracker.CachedSystemPrompt.of("v2", "", "");
        var result2 = tracker.getOrBuild(sessionId, () -> prompt2);
        assertThat(result2.fullPrompt()).contains("v2");
    }

    @Test
    void cachedSystemPrompt_joinsTiersWithSeparators() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("stable content", "context content", "volatile content");
        assertThat(prompt.fullPrompt()).isEqualTo("stable content\n\ncontext content\n\nvolatile content");
    }

    @Test
    void cachedSystemPrompt_handlesEmptyTiers() {
        var prompt = PromptCacheTracker.CachedSystemPrompt.of("stable", "", null);
        assertThat(prompt.fullPrompt()).isEqualTo("stable");
    }

    @Test
    void applyAnthropicCacheControl_addsCacheControlToSystemMessage() {
        java.util.Map<String, Object> sysMsg = new java.util.LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", "system prompt");
        java.util.Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "user message");

        var result = tracker.applyAnthropicCacheControl(java.util.List.of(sysMsg, userMsg), "5m");
        assertThat(result).hasSize(2);
        // System message should have cache_control (as text part)
        @SuppressWarnings("unchecked")
        var content = (java.util.List<java.util.Map<String, Object>>) result.get(0).get("content");
        assertThat(content).isNotNull();
        assertThat(content.get(0)).containsKey("cache_control");
    }

    @Test
    void applyAnthropicCacheControl_emptyMessages_returnsEmpty() {
        var result = tracker.applyAnthropicCacheControl(java.util.List.of(), "5m");
        assertThat(result).isEmpty();
    }

    @Test
    void hashPrefix_stableForSameContent() {
        String h1 = PromptCacheTracker.hashPrefix("test content");
        String h2 = PromptCacheTracker.hashPrefix("test content");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hashPrefix_differentForDifferentContent() {
        String h1 = PromptCacheTracker.hashPrefix("content A");
        String h2 = PromptCacheTracker.hashPrefix("content B");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void getCacheStats_returnsValidStats() {
        tracker.markCached("s1", "hash1");
        tracker.isCacheValid("s1", "hash1"); // hit
        tracker.isCacheValid("s1", "wrong"); // miss
        var stats = tracker.getCacheStats();
        assertThat(stats).containsKey("totalHits");
        assertThat(stats.get("totalHits")).isEqualTo(1L);
    }
}