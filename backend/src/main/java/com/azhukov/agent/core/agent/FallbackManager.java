package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.FallbackConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages mid-turn model fallback — switching to alternate providers/models
 * when the primary model fails.
 * <p>
 * Mirrors Hermes fallback chain mechanism ({@code _fallback_chain},
 * {@code _fallback_index}, {@code _fallback_activated},
 * {@code _try_activate_fallback}, {@code _has_pending_fallback},
 * {@code _restore_primary_runtime}).
 * <p>
 * Fallback is <strong>turn-scoped</strong>: if activated during a turn, the
 * primary model is restored at the start of the next turn (unless a rate-limit
 * cooldown is still active).
 * <p>
 * The manager does NOT own the actual {@code ModelClient} construction — it
 * tracks the index into the chain and exposes the active config so the
 * runtime can build/swap the client as needed.
 */
@Slf4j
public class FallbackManager {

    private final List<FallbackConfig> chain;
    private final String primaryProvider;
    private final String primaryModel;
    private final String primaryBaseUrl;
    private final String primaryApiKey;

    private int fallbackIndex = 0;
    private int activeFallbackIndex = -1; // index of the currently active fallback, -1 = primary
    private boolean fallbackActivated = false;

    // Rate-limit cooldown — when set, the primary provider is skipped until
    // this timestamp. Mirrors Hermes {@code _rate_limited_until}.
    private volatile long rateLimitedUntil = 0;
    private static final long RATE_LIMIT_COOLDOWN_MS = 60_000;

    public FallbackManager(List<FallbackConfig> chain,
                           String primaryProvider, String primaryModel,
                           String primaryBaseUrl, String primaryApiKey) {
        this.chain = chain != null ? new ArrayList<>(chain) : new ArrayList<>();
        this.primaryProvider = primaryProvider != null ? primaryProvider : "openai-compatible";
        this.primaryModel = primaryModel != null ? primaryModel : "";
        this.primaryBaseUrl = primaryBaseUrl != null ? primaryBaseUrl : "";
        this.primaryApiKey = primaryApiKey != null ? primaryApiKey : "";
    }

    /**
     * Check if there's a next fallback available in the chain.
     * Mirrors Hermes {@code _has_pending_fallback()}.
     */
    public boolean hasPendingFallback() {
        return fallbackIndex < chain.size();
    }

    /**
     * Activate the next fallback in the chain.
     * Mirrors Hermes {@code _try_activate_fallback()}.
     *
     * @return the FallbackConfig to switch to, or null if the chain is exhausted
     */
    public FallbackConfig activateFallback() {
        if (fallbackIndex >= chain.size()) {
            log.debug("Fallback chain exhausted (index={}, chain size={})", fallbackIndex, chain.size());
            return null;
        }

        FallbackConfig next = chain.get(fallbackIndex);
        fallbackIndex++;

        // Skip entries that match the current provider+model (deduplication
        // — mirrors Hermes issue #22548: avoid falling back to the same backend)
        while (next != null && isSameAsCurrent(next)) {
            log.debug("Skipping fallback entry matching current provider/model: {}/{}",
                next.getProvider(), next.getModel());
            if (fallbackIndex >= chain.size()) {
                return null;
            }
            next = chain.get(fallbackIndex);
            fallbackIndex++;
        }

        if (next == null) {
            return null;
        }

        // Track the index of this activated fallback (for getCurrentProvider/Model)
        // fallbackIndex was already incremented past this entry, so the active index is fallbackIndex - 1
        activeFallbackIndex = fallbackIndex - 1;
        fallbackActivated = true;
        log.info("🔄 Activating fallback: {} via {} (index {}/{})",
            next.getModel(), next.getProvider(), fallbackIndex, chain.size());
        return next;
    }

    /**
     * Check if a fallback config matches the current active provider/model.
     * When fallback is active, compares against the current fallback; otherwise the primary.
     */
    private boolean isSameAsCurrent(FallbackConfig config) {
        if (config == null) return true;
        String currentProvider = getCurrentProvider();
        String currentModel = getCurrentModel();
        String currentBaseUrl = getCurrentBaseUrl();
        // Match on provider+model
        if (config.getProvider() != null && config.getProvider().equalsIgnoreCase(currentProvider)
            && config.getModel() != null && config.getModel().equalsIgnoreCase(currentModel)) {
            return true;
        }
        // Match on baseUrl
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            && currentBaseUrl != null && !currentBaseUrl.isBlank()
            && config.getBaseUrl().equalsIgnoreCase(currentBaseUrl)) {
            return true;
        }
        return false;
    }

    /**
     * Get the current active provider.
     * If fallback is activated, returns the fallback provider; otherwise the primary.
     */
    public String getCurrentProvider() {
        if (fallbackActivated && activeFallbackIndex >= 0 && activeFallbackIndex < chain.size()) {
            return chain.get(activeFallbackIndex).getProvider();
        }
        return primaryProvider;
    }

    /**
     * Get the current active model.
     * If fallback is activated, returns the fallback model; otherwise the primary.
     */
    public String getCurrentModel() {
        if (fallbackActivated && activeFallbackIndex >= 0 && activeFallbackIndex < chain.size()) {
            return chain.get(activeFallbackIndex).getModel();
        }
        return primaryModel;
    }

    /**
     * Get the current active base URL.
     */
    public String getCurrentBaseUrl() {
        if (fallbackActivated && activeFallbackIndex >= 0 && activeFallbackIndex < chain.size()) {
            return chain.get(activeFallbackIndex).getBaseUrl();
        }
        return primaryBaseUrl;
    }

    /**
     * Get the current active API key.
     */
    public String getCurrentApiKey() {
        if (fallbackActivated && activeFallbackIndex >= 0 && activeFallbackIndex < chain.size()) {
            return chain.get(activeFallbackIndex).getApiKey();
        }
        return primaryApiKey;
    }

    /**
     * Whether fallback has been activated during this turn.
     */
    public boolean isFallbackActivated() {
        return fallbackActivated;
    }

    /**
     * Set the rate-limit cooldown timestamp.
     * Mirrors Hermes {@code _rate_limited_until = monotonic() + 60s}.
     */
    public void setRateLimitCooldown() {
        this.rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
    }

    /**
     * Check if the primary provider is still in rate-limit cooldown.
     */
    public boolean isPrimaryRateLimited() {
        return System.currentTimeMillis() < rateLimitedUntil;
    }

    /**
     * Restore the primary model/provider.
     * Mirrors Hermes {@code _restore_primary_runtime()}.
     * <p>
     * Called at the start of each new turn. If fallback was activated during
     * the previous turn and the rate-limit cooldown has expired, restores
     * the primary provider.
     *
     * @return true if primary was restored (fallback was active), false otherwise
     */
    public boolean restorePrimary() {
        if (!fallbackActivated) {
            // Reset index even if no fallback was activated (fixes Hermes #20465)
            fallbackIndex = 0;
            activeFallbackIndex = -1;
            return false;
        }

        // Check rate-limit cooldown — if still active, stay on fallback
        if (isPrimaryRateLimited()) {
            log.debug("Primary provider still in rate-limit cooldown, staying on fallback");
            return false;
        }

        log.info("🔄 Restoring primary model: {} via {}", primaryModel, primaryProvider);
        fallbackActivated = false;
        fallbackIndex = 0;
        activeFallbackIndex = -1;
        return true;
    }

    /**
     * Reset the fallback manager to the beginning of the chain.
     * Called at the start of each turn.
     */
    public void reset() {
        fallbackIndex = 0;
        activeFallbackIndex = -1;
        fallbackActivated = false;
    }

    /**
     * Get the primary provider.
     */
    public String getPrimaryProvider() {
        return primaryProvider;
    }

    /**
     * Get the primary model.
     */
    public String getPrimaryModel() {
        return primaryModel;
    }

    /**
     * Get the primary base URL.
     */
    public String getPrimaryBaseUrl() {
        return primaryBaseUrl;
    }

    /**
     * Get the chain size.
     */
    public int getChainSize() {
        return chain.size();
    }

    /**
     * Get the current fallback index (for diagnostics).
     */
    public int getFallbackIndex() {
        return fallbackIndex;
    }
}