package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultRedactor implements Redactor {

    private final AgentProperties properties;
    private final Pattern envVarPattern = Pattern.compile("\\b([A-Z_][A-Z0-9_]*)=(\\S+)");

    // ─── Built-in vendor patterns (compiled once) ───

    /** Hint + pattern pairs for optimized matching. */
    private record BuiltinPattern(String hint, Pattern pattern) {}

    private static final BuiltinPattern[] BUILTIN_PATTERNS = {
        // API keys
        new BuiltinPattern("sk-", Pattern.compile("sk-[a-zA-Z0-9]{20,}")),
        new BuiltinPattern("ghp_", Pattern.compile("ghp_[a-zA-Z0-9]{36}")),
        new BuiltinPattern("gho_", Pattern.compile("gho_[a-zA-Z0-9]{36}")),
        new BuiltinPattern("github_pat_", Pattern.compile("github_pat_[a-zA-Z0-9_]{82}")),
        new BuiltinPattern("xox", Pattern.compile("xox[baprs]-[a-zA-Z0-9-]+")),
        new BuiltinPattern("AIza", Pattern.compile("AIza[a-zA-Z0-9_-]{35}")),
        new BuiltinPattern("pplx-", Pattern.compile("pplx-[a-zA-Z0-9]+")),
        new BuiltinPattern("fal_", Pattern.compile("fal_[a-zA-Z0-9]+")),
        // JWT
        new BuiltinPattern("eyJ", Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]*")),
        // PEM private key
        new BuiltinPattern("PRIVATE KEY", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
        // DB connection string
        new BuiltinPattern("://", Pattern.compile("(postgres|postgresql|mongodb|mysql|redis)://[^\\s]+:[^\\s]+@[^\\s]+")),
        // Telegram bot token
        new BuiltinPattern(":AA", Pattern.compile("\\d{8,10}:AA[a-zA-Z0-9_-]{33}")),
        // URL with credentials
        new BuiltinPattern("://", Pattern.compile("https?://[^:\\s]+:[^@\\s]+@[^\\s]+")),
        // Authorization header
        new BuiltinPattern("earer ", Pattern.compile("[Bb]earer [a-zA-Z0-9_.-]+")),
        // Slack token (also covered by xox above, but listed for clarity)
        new BuiltinPattern("xox", Pattern.compile("xox[baprs]-[a-zA-Z0-9-]+")),
    };

    @Override
    public String redact(String output) {
        if (output == null) {
            return null;
        }
        if (output.isEmpty()) {
            return output;
        }
        if (!properties.getSecurity().isRedactEnabled()) {
            return output;
        }

        String result = output;

        // 1. Apply built-in vendor patterns first (with substring pre-check optimization)
        if (properties.getSecurity().isRedactSecrets()) {
            for (BuiltinPattern bp : BUILTIN_PATTERNS) {
                if (result.contains(bp.hint())) {
                    result = bp.pattern().matcher(result).replaceAll("[REDACTED]");
                }
            }
        }

        // 2. Apply config patterns
        List<String> patterns = properties.getSecurity().getSecretPatterns();
        if (patterns != null) {
            for (String regex : patterns) {
                try {
                    result = result.replaceAll(regex, "[REDACTED]");
                } catch (PatternSyntaxException e) {
                    // ignore invalid regex
                }
            }
        }

        // 3. Apply env var redaction
        result = redactEnvVars(result);
        return result;
    }

    @Override
    public String redactEnvVars(String output) {
        if (output == null) {
            return null;
        }
        List<String> sensitive = properties.getSecurity().getSensitiveEnvVarPatterns();
        if (sensitive == null || sensitive.isEmpty()) {
            return output;
        }
        String result = output;
        var matcher = envVarPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            for (String pattern : sensitive) {
                if (matches(name, pattern)) {
                    matcher.appendReplacement(sb, name + "=[REDACTED]");
                    break;
                }
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean matches(String name, String pattern) {
        String lowerName = name.toLowerCase();
        String lowerPattern = pattern.toLowerCase();
        if (lowerPattern.startsWith("*") || lowerPattern.endsWith("*")) {
            String glob = lowerPattern.replace("*", "");
            return lowerName.contains(glob);
        }
        return lowerName.equals(lowerPattern);
    }
}