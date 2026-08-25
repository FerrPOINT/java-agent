package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the runtime footer appended to bot responses.
 * <p>
 * Format: {@code "kimi-k2.6 · 23% · ~/work"} — model short name, context percentage, working directory.
 * Config-driven via {@code bot.footer.enabled} and {@code bot.footer.fields}.
 */
@Component
@RequiredArgsConstructor
public class RuntimeFooter {

    private static final String SEPARATOR = " · ";

    private final BotProperties properties;

    /**
     * Format the footer string from runtime metadata.
     *
     * @param model           the full model name (e.g. "moonshotai/kimi-k2.6")
     * @param contextTokens   current context tokens used
     * @param contextLength   total context length
     * @param cwd             current working directory path
     * @return formatted footer string, or empty string if disabled or no fields
     */
    public String format(String model, int contextTokens, int contextLength, String cwd) {
        return format(model, contextTokens, contextLength, cwd, -1);
    }

    /**
     * Format the footer string from runtime metadata, with optional latency.
     *
     * @param model           the full model name (e.g. "moonshotai/kimi-k2.6")
     * @param contextTokens   current context tokens used
     * @param contextLength   total context length
     * @param cwd             current working directory path
     * @param turnSeconds     wall-clock turn duration in seconds (negative = skip)
     * @return formatted footer string, or empty string if disabled or no fields
     */
    public String format(String model, int contextTokens, int contextLength, String cwd, long turnSeconds) {
        if (!properties.getFooter().isEnabled()) {
            return "";
        }
        List<String> fields = properties.getFooter().getFields();
        if (fields == null || fields.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n");
        boolean first = true;
        for (String field : fields) {
            String value = switch (field) {
                case "model" -> shortModelName(model);
                case "context_pct" -> formatContextPct(contextTokens, contextLength);
                case "latency" -> turnSeconds >= 0 ? formatLatency(turnSeconds) : null;
                case "cwd" -> formatCwd(cwd);
                default -> null;
            };
            if (value != null && !value.isEmpty()) {
                if (!first) sb.append(SEPARATOR);
                sb.append(value);
                first = false;
            }
        }
        if (first) {
            return ""; // no valid fields produced output
        }
        return sb.toString();
    }

    /**
     * Humanize a turn duration: &lt;1s, 22s, 1m05s.
     */
    static String formatLatency(long seconds) {
        if (seconds < 1) return "<1s";
        long total = Math.round(seconds);
        if (total < 60) return total + "s";
        long m = total / 60;
        long sec = total % 60;
        return String.format("%dm%02ds", m, sec);
    }

    /**
     * Short model name: drop vendor prefix (everything before first '/').
     * E.g. "moonshotai/kimi-k2.6" → "kimi-k2.6"
     */
    static String shortModelName(String model) {
        if (model == null || model.isBlank()) return "";
        int slash = model.indexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    /**
     * Context percentage: contextTokens / contextLength * 100, rounded to int.
     */
    static String formatContextPct(int contextTokens, int contextLength) {
        if (contextLength <= 0) return "0%";
        int pct = (int) Math.round((double) contextTokens / contextLength * 100);
        pct = Math.max(0, Math.min(100, pct));
        return pct + "%";
    }

    /**
     * Format working directory: replace $HOME prefix with ~.
     */
    static String formatCwd(String cwd) {
        if (cwd == null || cwd.isBlank()) return "";
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty() && cwd.startsWith(home)) {
            return "~" + cwd.substring(home.length());
        }
        return cwd;
    }
}