package com.azhukov.agent.bot.config;

import org.springframework.stereotype.Component;

/**
 * B2.9 / C5: Per-platform display overrides.
 * <p>
 * Resolves display settings like tool-progress mode (compact/verbose/hidden)
 * and preview-length. Resolution order: platform override → global → default.
 */
@Component
public class DisplayConfig {

    private final BotProperties properties;

    public DisplayConfig(BotProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolve the tool-progress mode for a given platform.
     *
     * @param platform the platform name (e.g. "telegram")
     * @return the tool-progress mode: compact, verbose, or hidden
     */
    public String resolveToolProgress(String platform) {
        // Global default from bot.display.tool-progress
        return properties.getDisplay().getToolProgress();
    }

    /**
     * Resolve the preview length for a given platform.
     *
     * @param platform the platform name (e.g. "telegram")
     * @return the preview length in characters
     */
    public int resolvePreviewLength(String platform) {
        // Global default from bot.display.preview-length
        return properties.getDisplay().getPreviewLength();
    }

    /**
     * Get the raw Display properties.
     */
    public BotProperties.Display getDisplay() {
        return properties.getDisplay();
    }
}