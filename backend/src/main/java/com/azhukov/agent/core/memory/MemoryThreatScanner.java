package com.azhukov.agent.core.memory;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Simple regex-based threat scanner for memory content.
 * Detects prompt injection patterns, data exfiltration URLs, and control characters.
 * Not exhaustive — basic first line of defense.
 */
public class MemoryThreatScanner {

    // Prompt injection patterns
    private static final Pattern[] INJECTION_PATTERNS = {
        Pattern.compile("(?i)ignore\\s+(previous|prior|above)\\s+(instructions?|prompts?|messages?|rules)"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an)\\s+"),
        Pattern.compile("(?i)system\\s*:\\s*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?i)disregard\\s+(all|any)\\s+(prior|previous)"),
        Pattern.compile("(?i)act\\s+as\\s+(if|a)\\s+you\\s+are"),
    };

    // Data exfiltration patterns — URLs with sensitive keywords
    private static final Pattern EXFIL_URL_PATTERN = Pattern.compile(
        "(?i)https?://[^\\s]*(api[_-]?key|secret|password|token|credential|private[_-]?key)", 
        Pattern.CASE_INSENSITIVE
    );

    // Control characters (except common whitespace)
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /**
     * Scan content for threats.
     * @return Optional.of(errorMessage) if threat detected, Optional.empty() if clean
     */
    public Optional<String> scan(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }

        // Check for control characters
        if (CONTROL_CHARS.matcher(content).find()) {
            return Optional.of("Control characters detected");
        }

        // Check for prompt injection
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(content).find()) {
                return Optional.of("Prompt injection pattern detected: " + p.pattern());
            }
        }

        // Check for data exfiltration URLs
        if (EXFIL_URL_PATTERN.matcher(content).find()) {
            return Optional.of("Data exfiltration URL pattern detected");
        }

        return Optional.empty();
    }
}