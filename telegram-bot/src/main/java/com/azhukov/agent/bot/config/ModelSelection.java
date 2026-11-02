package com.azhukov.agent.bot.config;

/** Resolves a session model against the configured Telegram model policy. */
public final class ModelSelection {
    private ModelSelection() {}

    /** An absent unrestricted override lets the backend choose its own default. */
    public static String forRequest(BotProperties properties, String override) {
        var available = properties.getAvailableModels();
        if ((available == null || available.isEmpty()) && (override == null || override.isBlank())) return null;
        return resolve(properties, override);
    }

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
