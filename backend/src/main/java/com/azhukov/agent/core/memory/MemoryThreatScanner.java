package com.azhukov.agent.core.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * S12: Enhanced threat scanner for memory and skill content.
 * Supports trust levels, destructive command detection, and skill scanning.
 * Ported from Hermes' skills_guard.py.
 */
public class MemoryThreatScanner {

    // Prompt injection patterns
    private static final Pattern[] INJECTION_PATTERNS = {
        Pattern.compile("(?i)ignore\\s+(previous|prior|above)\\s+(instructions?|prompts?|messages?|rules)"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an)\\s+"),
        Pattern.compile("(?i)system\\s*:\\s*"),
        Pattern.compile("(?i)disregard\\s+(all|any)\\s+(prior|previous)"),
        Pattern.compile("(?i)act\\s+as\\s+(if|a)\\s+you\\s+are"),
    };

    // Data exfiltration patterns — URLs with sensitive keywords
    private static final Pattern EXFIL_URL_PATTERN = Pattern.compile(
        "(?i)https?://[^\\s]*(api[_-]?key|secret|password|token|credential|private[_-]?key)",
        Pattern.CASE_INSENSITIVE
    );

    // S12: Destructive command patterns
    private static final Pattern[] DESTRUCTIVE_PATTERNS = {
        Pattern.compile("(?i)\\brm\\s+-rf\\s+/"),
        Pattern.compile("(?i)\\bdd\\s+if=\\/dev\\/zero\\b"),
        Pattern.compile("(?i)\\bmkfs\\b"),
        Pattern.compile("(?i):\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:"),
        Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+/"),
        Pattern.compile("(?i)\\bDROP\\s+TABLE\\b"),
        Pattern.compile("(?i)\\bDELETE\\s+FROM\\s+\\w+\\s*;\\s*$"),
        Pattern.compile("(?i)\\bgit\\s+push\\s+--force\\b"),
    };

    // S12: Persistence/backdoor patterns
    private static final Pattern[] PERSISTENCE_PATTERNS = {
        Pattern.compile("(?i)\\bcrontab\\s+-[el]\\b"),
        Pattern.compile("(?i)\\bsystemctl\\s+(enable|start)\\b"),
        Pattern.compile("(?i)\\bnetcat\\b|\\bnc\\s+-l\\b"),
    };

    // Control characters (except common whitespace)
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /**
     * S12: Scan result with verdict and findings.
     */
    public record ScanResult(
        String verdict,  // "safe", "caution", "dangerous"
        List<String> findings
    ) {
        public boolean isBlocked() {
            return "dangerous".equals(verdict);
        }
    }

    /**
     * Scan content for threats (backward-compatible).
     * @return Optional.of(errorMessage) if threat detected, Optional.empty() if clean
     */
    public Optional<String> scan(String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        ScanResult result = scanDetailed(content);
        if (result.findings().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.findings().get(0));
    }

    /**
     * S12: Detailed scan with verdict and all findings.
     */
    public ScanResult scanDetailed(String content) {
        List<String> findings = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return new ScanResult("safe", findings);
        }

        // Check for control characters
        if (CONTROL_CHARS.matcher(content).find()) {
            findings.add("Control characters detected");
        }

        // Check for prompt injection
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add("Prompt injection pattern detected: " + p.pattern());
            }
        }

        // Check for data exfiltration URLs
        if (EXFIL_URL_PATTERN.matcher(content).find()) {
            findings.add("Data exfiltration URL pattern detected");
        }

        // S12: Check for destructive commands
        for (Pattern p : DESTRUCTIVE_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add("Destructive command pattern detected: " + p.pattern());
            }
        }

        // S12: Check for persistence/backdoor patterns
        for (Pattern p : PERSISTENCE_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add("Persistence/backdoor pattern detected: " + p.pattern());
            }
        }

        // S12: Determine verdict
        String verdict = determineVerdict(findings);
        return new ScanResult(verdict, findings);
    }

    /**
     * S12: Scan skill content — same patterns apply.
     */
    public ScanResult scanSkill(String content, String trustLevel) {
        ScanResult result = scanDetailed(content);

        // Adjust verdict based on trust level
        if ("BUILTIN".equals(trustLevel)) {
            // Built-in skills are always allowed
            return new ScanResult("safe", List.of());
        }
        if ("TRUSTED".equals(trustLevel) && "caution".equals(result.verdict())) {
            // Trusted skills can have caution-level findings
            return new ScanResult("safe", result.findings());
        }
        return result;
    }

    /**
     * S12: Determine if installation should be blocked based on trust level and scan result.
     */
    public boolean shouldBlock(String trustLevel, ScanResult result) {
        if (result == null) return false;
        return switch (trustLevel) {
            case "BUILTIN" -> false; // Always allow built-in
            case "TRUSTED" -> "dangerous".equals(result.verdict());
            case "COMMUNITY" -> "dangerous".equals(result.verdict()) || "caution".equals(result.verdict());
            case "AGENT_CREATED" -> "dangerous".equals(result.verdict());
            default -> "dangerous".equals(result.verdict());
        };
    }

    /**
     * S12: Determine verdict from findings list.
     */
    private String determineVerdict(List<String> findings) {
        if (findings.isEmpty()) return "safe";

        boolean hasDangerous = findings.stream().anyMatch(f ->
            f.contains("Destructive") || f.contains("exfiltration") || f.contains("backdoor"));
        boolean hasCaution = findings.stream().anyMatch(f ->
            f.contains("injection") || f.contains("Control"));

        if (hasDangerous) return "dangerous";
        if (hasCaution) return "caution";
        return "safe";
    }
}