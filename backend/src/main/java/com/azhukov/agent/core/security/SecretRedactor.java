package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SecretRedactor {

    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
        Pattern.compile("(?i)(api[_-]?key|apikey|api_secret|secret[_-]?key|auth[_-]?token|password|passwd|pwd|token|bearer|private[_-]?key)\s*[:=]\s*[\"']?([A-Za-z0-9_\\-./+=]{8,})[\"']?"),
        Pattern.compile("(?i)(sk-[a-z0-9]{20,})"),
        Pattern.compile("(?i)(gh[pousr]_[A-Za-z0-9_]{20,})"),
        Pattern.compile("(?i)(https?://[^:]+:[^@]+@)"),
        Pattern.compile("(?i)Authorization\s*[:=]\s*[\"']?([A-Za-z0-9_\\-./+=\s]+)[\"']?")
    );

    private final List<Pattern> patterns;
    private final boolean enabled;

    public SecretRedactor(AgentProperties properties) {
        this.enabled = properties.getSecurity() == null || properties.getSecurity().isRedactEnabled();
        boolean redactSecrets = properties.getSecurity() == null || properties.getSecurity().isRedactSecrets();
        List<String> custom = properties.getSecurity() != null ? properties.getSecurity().getSecretPatterns() : null;
        this.patterns = new ArrayList<>();
        if (redactSecrets) {
            patterns.addAll(DEFAULT_PATTERNS);
        }
        if (custom != null) {
            for (String p : custom) {
                try {
                    patterns.add(Pattern.compile(p));
                } catch (Exception e) { log.warn("Failed to compile custom secret redaction pattern '{}': {}", p, e.getMessage()); }
            }
        }
    }

    public String redact(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern p : patterns) {
            result = p.matcher(result).replaceAll("[REDACTED:$1]");
        }
        return result;
    }
}
