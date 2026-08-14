package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.FallbackConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FallbackManager}.
 * <p>
 * Tests fallback chain activation, deduplication, primary restoration,
 * and rate-limit cooldown behavior — mirroring Hermes fallback semantics.
 */
class FallbackManagerTest {

    private FallbackManager manager;
    private List<FallbackConfig> chain;

    @BeforeEach
    void setUp() {
        chain = new ArrayList<>();
        chain.add(makeFallback("openai-compatible", "gpt-4o", "https://api.openai.com", "key1"));
        chain.add(makeFallback("anthropic", "claude-3", "https://api.anthropic.com", "key2"));
        chain.add(makeFallback("openai-compatible", "gpt-4o-mini", "https://api.openai.com", "key3"));

        manager = new FallbackManager(chain, "openai-compatible", "gpt-4o",
            "https://api.openai.com", "primary-key");
    }

    private FallbackConfig makeFallback(String provider, String model, String baseUrl, String apiKey) {
        FallbackConfig cfg = new FallbackConfig();
        cfg.setProvider(provider);
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrl);
        cfg.setApiKey(apiKey);
        return cfg;
    }

    @Test
    @DisplayName("hasPendingFallback returns true when chain has entries")
    void hasPendingFallback_returnsTrue() {
        assertThat(manager.hasPendingFallback()).isTrue();
    }

    @Test
    @DisplayName("hasPendingFallback returns false when chain is empty")
    void hasPendingFallback_returnsFalseWhenEmpty() {
        FallbackManager emptyManager = new FallbackManager(null, "openai", "gpt-4", "url", "key");
        assertThat(emptyManager.hasPendingFallback()).isFalse();
    }

    @Test
    @DisplayName("activateFallback returns the next config and increments index")
    void activateFallback_returnsNextConfig() {
        FallbackConfig first = manager.activateFallback();
        assertThat(first).isNotNull();
        // First entry should be skipped because it matches the primary (openai-compatible/gpt-4o)
        // So the first non-duplicate should be anthropic/claude-3
        assertThat(first.getProvider()).isEqualTo("anthropic");
        assertThat(first.getModel()).isEqualTo("claude-3");

        FallbackConfig second = manager.activateFallback();
        assertThat(second).isNotNull();
        assertThat(second.getProvider()).isEqualTo("openai-compatible");
        assertThat(second.getModel()).isEqualTo("gpt-4o-mini");

        // Chain exhausted
        FallbackConfig third = manager.activateFallback();
        assertThat(third).isNull();
        assertThat(manager.hasPendingFallback()).isFalse();
    }

    @Test
    @DisplayName("activateFallback skips entries matching primary provider+model")
    void activateFallback_skipsDuplicateProviderModel() {
        // The first chain entry (openai-compatible/gpt-4o) matches the primary,
        // so it should be skipped
        FallbackConfig first = manager.activateFallback();
        assertThat(first).isNotNull();
        assertThat(first.getModel()).isEqualTo("claude-3");
    }

    @Test
    @DisplayName("activateFallback skips entries matching primary baseUrl")
    void activateFallback_skipsDuplicateBaseUrl() {
        FallbackManager mgr = new FallbackManager(
            List.of(makeFallback("different", "model-x", "https://api.openai.com", "key")),
            "openai-compatible", "gpt-4o", "https://api.openai.com", "primary-key"
        );
        // Should skip because baseUrl matches
        FallbackConfig result = mgr.activateFallback();
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getCurrentProvider/Model returns primary before fallback activation")
    void getCurrentProvider_returnsPrimaryBeforeActivation() {
        assertThat(manager.getCurrentProvider()).isEqualTo("openai-compatible");
        assertThat(manager.getCurrentModel()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("getCurrentProvider/Model returns fallback after activation")
    void getCurrentProvider_returnsFallbackAfterActivation() {
        manager.activateFallback();
        assertThat(manager.getCurrentProvider()).isEqualTo("anthropic");
        assertThat(manager.getCurrentModel()).isEqualTo("claude-3");
    }

    @Test
    @DisplayName("isFallbackActivated is false before activation, true after")
    void isFallbackActivated_flagTransitions() {
        assertThat(manager.isFallbackActivated()).isFalse();
        manager.activateFallback();
        assertThat(manager.isFallbackActivated()).isTrue();
    }

    @Test
    @DisplayName("restorePrimary resets fallback state when fallback was activated")
    void restorePrimary_resetsWhenFallbackActive() {
        manager.activateFallback();
        assertThat(manager.isFallbackActivated()).isTrue();
        assertThat(manager.getCurrentProvider()).isEqualTo("anthropic");

        boolean restored = manager.restorePrimary();
        assertThat(restored).isTrue();
        assertThat(manager.isFallbackActivated()).isFalse();
        assertThat(manager.getCurrentProvider()).isEqualTo("openai-compatible");
        assertThat(manager.getCurrentModel()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("restorePrimary returns false when no fallback was activated")
    void restorePrimary_returnsFalseWhenNoFallback() {
        boolean restored = manager.restorePrimary();
        assertThat(restored).isFalse();
    }

    @Test
    @DisplayName("restorePrimary stays on fallback when rate-limit cooldown is active")
    void restorePrimary_staysOnFallbackWhenRateLimited() {
        manager.setRateLimitCooldown();
        manager.activateFallback();
        assertThat(manager.isFallbackActivated()).isTrue();

        boolean restored = manager.restorePrimary();
        assertThat(restored).isFalse();
        assertThat(manager.isFallbackActivated()).isTrue();
    }

    @Test
    @DisplayName("setRateLimitCooldown sets a 60s cooldown")
    void setRateLimitCooldown_sets60sCooldown() {
        long before = System.currentTimeMillis();
        manager.setRateLimitCooldown();
        assertThat(manager.isPrimaryRateLimited()).isTrue();
        // The cooldown should be roughly 60s in the future
        long after = System.currentTimeMillis();
        assertThat(manager.isPrimaryRateLimited()).isTrue(); // still rate limited
    }

    @Test
    @DisplayName("reset clears fallback state")
    void reset_clearsState() {
        manager.activateFallback();
        manager.reset();
        assertThat(manager.isFallbackActivated()).isFalse();
        assertThat(manager.getFallbackIndex()).isEqualTo(0);
        assertThat(manager.hasPendingFallback()).isTrue();
    }

    @Test
    @DisplayName("getChainSize returns chain length")
    void getChainSize_returnsChainLength() {
        assertThat(manager.getChainSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("activateFallback on empty chain returns null")
    void activateFallback_emptyChainReturnsNull() {
        FallbackManager emptyManager = new FallbackManager(null, "openai", "gpt-4", "url", "key");
        assertThat(emptyManager.activateFallback()).isNull();
        assertThat(emptyManager.hasPendingFallback()).isFalse();
    }

    @Test
    @DisplayName("getCurrentBaseUrl/ApiKey return primary before activation, fallback after")
    void getCurrentBaseUrlAndApiKey_trackActiveConfig() {
        assertThat(manager.getCurrentBaseUrl()).isEqualTo("https://api.openai.com");
        assertThat(manager.getCurrentApiKey()).isEqualTo("primary-key");

        manager.activateFallback();
        assertThat(manager.getCurrentBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(manager.getCurrentApiKey()).isEqualTo("key2");
    }
}