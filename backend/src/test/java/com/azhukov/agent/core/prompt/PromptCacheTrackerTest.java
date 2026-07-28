package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
    void markCachedAndValidate() {
        tracker.markCached("session-1", "hash123");
        boolean valid = tracker.isCacheValid("session-1", "hash123");
        assertThat(valid).isTrue();
    }

    @Test
    void invalidateClearsCache() {
        tracker.markCached("session-1", "hash123");
        tracker.invalidate("session-1");
        boolean valid = tracker.isCacheValid("session-1", "hash123");
        assertThat(valid).isFalse();
    }

    @Test
    void differentHashInvalidatesCache() {
        tracker.markCached("session-1", "hash123");
        boolean valid = tracker.isCacheValid("session-1", "hash456");
        assertThat(valid).isFalse();
    }

    @Test
    void getCacheStatsReturnsMap() {
        tracker.markCached("session-1", "hash123");
        tracker.isCacheValid("session-1", "hash123"); // hit
        tracker.isCacheValid("session-2", "hash999"); // miss
        Map<String, Object> stats = tracker.getCacheStats();
        assertThat(stats).containsKey("cachedSessions");
        assertThat(stats.get("cachedSessions")).isEqualTo(1);
        assertThat(stats).containsKey("totalHits");
        assertThat(stats).containsKey("totalMisses");
    }

    @Test
    void hashPrefixIsDeterministic() {
        String h1 = PromptCacheTracker.hashPrefix("test prefix");
        String h2 = PromptCacheTracker.hashPrefix("test prefix");
        assertThat(h1).isEqualTo(h2);
        String h3 = PromptCacheTracker.hashPrefix("different prefix");
        assertThat(h1).isNotEqualTo(h3);
    }
}