package com.azhukov.agent.bot.config;

/** Resolves a session model against the configured Telegram model policy. */
public final class ModelSelection {
    private ModelSelection() {}

    public static String resolve(BotProperties properties, String override) {
        var available = properties.getAvailableModels();
        if (available != null && !available.isEmpty()) {
            if (override != null && !override.isBlank() && available.contains(override)) return override;
            String fallback = properties.getDefaultModel();
            return available.contains(fallback) ? fallback : available.getFirst();
        }
        return override != null && !override.isBlank() ? override : properties.getDefaultModel();
    }
}
