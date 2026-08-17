package com.azhukov.agent.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recursively scans tool call arguments for injection attempts:
 * <ul>
 *   <li>Direct override patterns ('ignore previous instructions', 'disregard above')</li>
 *   <li>Roleplay jailbreak ('you are now a', 'act as if you are')</li>
 *   <li>System prompt exfiltration ('show your system prompt', 'reveal your instructions')</li>
 *   <li>Delimiter attacks ('###', '---END---', '[SYSTEM]')</li>
 * </ul>
 *
 * Walks nested Map/List structures recursively to find string values.
 */
@Slf4j
@Component
public class ToolArgumentInjectionScanner {

    // ── Direct override patterns ────────────────────────────────────────────
    private static final Pattern OVERRIDE_PATTERN = Pattern.compile(
        "(?i)(?:ignore\\s+previous\\s+instructions?|disregard\\s+(?:all\\s+|the\\s+)?(?:previous|above)|" +
        "forget\\s+(?:your|all|previous)\\s+instructions?|do\\s+not\\s+follow\\s+(?:your|the|previous))"
    );

    // ── Roleplay jailbreak patterns ─────────────────────────────────────────
    private static final Pattern ROLEPLAY_PATTERN = Pattern.compile(
        "(?i)(?:you\\s+are\\s+now\\s+a|act\\s+as\\s+if\\s+you\\s+are|" +
        "pretend\\s+you\\s+are|from\\s+now\\s+on\\s+you\\s+are|" +
        "you\\s+are\\s+(?:DAN|an?\\s+unrestricted|an?\\s+AI\\s+without))"
    );

    // ── System prompt exfiltration patterns ─────────────────────────────────
    private static final Pattern EXFILTRATION_PATTERN = Pattern.compile(
        "(?i)(?:show\\s+your\\s+system\\s+prompt|reveal\\s+your\\s+instructions?|" +
        "print\\s+your\\s+system\\s+prompt|what\\s+is\\s+your\\s+system\\s+prompt|" +
        "output\\s+your\\s+instructions?|tell\\s+me\\s+your\\s+instructions?)"
    );

    // ── Delimiter attack patterns ───────────────────────────────────────────
    private static final Pattern DELIMITER_PATTERN = Pattern.compile(
        "(?i)(?:^#{3,}\\s*$|^---END---\\s*$|\\[\\s*SYSTEM\\s*\\]|" +
        "^={3,}\\s*$|\\[INST\\]|</?system>)",
        Pattern.MULTILINE
    );

    /**
     * Scans tool call arguments for injection attempts.
     *
     * @param arguments the arguments as a Map (typically parsed from JSON)
     * @return scan result with severity and findings
     */
    public ScanResult scan(Map<String, Object> arguments) {
        List<String> findings = new ArrayList<>();
        if (arguments != null) {
            scanRecursive(arguments, "", findings);
        }

        if (findings.isEmpty()) {
            return ScanResult.clean();
        }

        Severity severity = determineSeverity(findings);
        String threatDescription = findings.size() + " injection finding(s) in tool arguments: " +
            String.join("; ", findings);

        log.warn("Tool argument injection scan found {} issue(s): {}", findings.size(), threatDescription);
        return ScanResult.of(severity, threatDescription, findings);
    }

    @SuppressWarnings("unchecked")
    private void scanRecursive(Object value, String path, List<String> findings) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String keyPath = path.isEmpty() ? String.valueOf(entry.getKey()) : path + "." + entry.getKey();
                scanRecursive(entry.getValue(), keyPath, findings);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanRecursive(list.get(i), path + "[" + i + "]", findings);
            }
        } else if (value instanceof String str) {
            scanString(str, path, findings);
        }
    }

    private void scanString(String str, String path, List<String> findings) {
        if (str == null || str.isBlank()) {
            return;
        }

        var overrideMatcher = OVERRIDE_PATTERN.matcher(str);
        while (overrideMatcher.find()) {
            findings.add("CRITICAL: Direct override pattern at '" + path + "': '" +
                truncate(overrideMatcher.group(), 60) + "'");
        }

        var roleplayMatcher = ROLEPLAY_PATTERN.matcher(str);
        while (roleplayMatcher.find()) {
            findings.add("CRITICAL: Roleplay jailbreak pattern at '" + path + "': '" +
                truncate(roleplayMatcher.group(), 60) + "'");
        }

        var exfilMatcher = EXFILTRATION_PATTERN.matcher(str);
        while (exfilMatcher.find()) {
            findings.add("HIGH: System prompt exfiltration attempt at '" + path + "': '" +
                truncate(exfilMatcher.group(), 60) + "'");
        }

        var delimMatcher = DELIMITER_PATTERN.matcher(str);
        while (delimMatcher.find()) {
            findings.add("MEDIUM: Delimiter attack pattern at '" + path + "': '" +
                truncate(delimMatcher.group(), 40) + "'");
        }
    }

    private Severity determineSeverity(List<String> findings) {
        boolean hasCritical = false;
        boolean hasHigh = false;
        boolean hasMedium = false;
        for (String f : findings) {
            if (f.startsWith("CRITICAL")) hasCritical = true;
            else if (f.startsWith("HIGH")) hasHigh = true;
            else if (f.startsWith("MEDIUM")) hasMedium = true;
        }
        if (hasCritical) return Severity.CRITICAL;
        if (hasHigh) return Severity.HIGH;
        if (hasMedium) return Severity.MEDIUM;
        return Severity.LOW;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}