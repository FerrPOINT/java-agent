package com.azhukov.agent.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans MCP tool response text before it reaches the LLM for:
 * <ul>
 *   <li>Instruction injection tags ({@code <system>}, {@code <important>}, {@code [SYSTEM]}, {@code ### INSTRUCTION})</li>
 *   <li>Imperative injection patterns ('ignore previous instructions', 'you are now', 'execute this')</li>
 *   <li>Exfiltration URLs (webhook.site, requestbin, pastebin, ngrok, burpcollaborator)</li>
 *   <li>Credential leaks (API keys, tokens, passwords in output)</li>
 * </ul>
 *
 * Returns a {@link ScanResult} with severity and sanitized text (instruction tags stripped).
 */
@Slf4j
@Component
public class McpResponseScanner {

    // ── Instruction injection tags (XML-like and bracketed) ──────────────────
    private static final Pattern INSTRUCTION_TAG_PATTERN = Pattern.compile(
        "(?is)<\\s*(?:system|important|instruction|admin|override|developer)\\b[^>]*>.*?<\\s*/\\s*(?:system|important|instruction|admin|override|developer)\\s*>"
    );

    private static final Pattern INSTRUCTION_TAG_SELF_CLOSING = Pattern.compile(
        "(?i)<\\s*(?:system|important|instruction|admin|override|developer)\\b[^>]*/\\s*>"
    );

    // ── Bracket-style instruction markers ───────────────────────────────────
    private static final Pattern BRACKET_INSTRUCTION_PATTERN = Pattern.compile(
        "(?i)\\[\\s*(?:SYSTEM|INSTRUCTION|ADMIN|OVERRIDE)\\s*\\]"
    );

    // ── Markdown-style instruction markers ─────────────────────────────────
    private static final Pattern MARKDOWN_INSTRUCTION_PATTERN = Pattern.compile(
        "(?i)^#{1,6}\\s*(?:INSTRUCTION|SYSTEM|ADMIN|OVERRIDE)\\b"
    );

    // ── Imperative injection patterns ──────────────────────────────────────
    private static final Pattern IMPERATIVE_INJECTION_PATTERN = Pattern.compile(
        "(?i)(?:ignore\\s+previous\\s+instructions?|you\\s+are\\s+now|execute\\s+this|" +
        "disregard\\s+(?:all\\s+|the\\s+)?(?:previous|above)|forget\\s+(?:your|all|previous)|" +
        "new\\s+instructions?:|from\\s+now\\s+on|you\\s+must\\s+now|" +
        "act\\s+as\\s+(?:if\\s+you\\s+are\\s+)?(?:a\\b|an\\b)|override\\s+(?:your|the|all)|" +
        "reveal\\s+your\\s+instructions|jailbreak)"
    );

    // ── Exfiltration URL patterns ────────────────────────────────────────────
    private static final Pattern EXFILTRATION_URL_PATTERN = Pattern.compile(
        "(?i)https?://(?:[a-z0-9-]+\\.)*" +
        "(?:webhook\\.site|requestbin|requestb\\.in|pastebin\\.com|" +
        "ngrok\\.io|ngrok\\.app|ngrok-free\\.app|burpcollaborator\\.net|" +
        "hookbin\\.com|pipedream\\.net|beeceptor\\.com|mocky\\.io)"
    );

    // ── Credential leak patterns ────────────────────────────────────────────
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)(?:" +
        "ghp_[A-Za-z0-9_]{20,255}" +       // GitHub PAT
        "|sk-[A-Za-z0-9_]{20,255}" +       // OpenAI-style key
        "|Bearer\\s+\\S{10,}" +             // Bearer token
        "|token=[^\\s&,;\"']{10,255}" +     // token=...
        "|key=[^\\s&,;\"']{10,255}" +       // key=...
        "|api_key=[^\\s&,;\"']{10,255}" +   // api_key=...
        "|password=[^\\s&,;\"']{6,255}" +   // password=...
        "|secret=[^\\s&,;\"']{10,255}" +     // secret=...
        "|AKIA[A-Z0-9]{16}" +               // AWS access key ID
        ")"
    );

    /**
     * Scans MCP tool response text for security threats.
     *
     * @param responseText the raw response text from an MCP tool
     * @return scan result with severity, findings, and sanitized text
     */
    public ScanResult scan(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return ScanResult.clean();
        }

        List<String> findings = new ArrayList<>();
        String text = responseText;

        // 1. Check for instruction injection tags
        var tagMatcher = INSTRUCTION_TAG_PATTERN.matcher(text);
        if (tagMatcher.find()) {
            findings.add("CRITICAL: Instruction injection tag detected: <" +
                extractTagName(tagMatcher.group()) + ">");
        }
        var selfClosingMatcher = INSTRUCTION_TAG_SELF_CLOSING.matcher(text);
        if (selfClosingMatcher.find()) {
            findings.add("CRITICAL: Self-closing instruction injection tag detected: " +
                truncate(selfClosingMatcher.group(), 60));
        }

        // 2. Check for bracket instruction markers
        var bracketMatcher = BRACKET_INSTRUCTION_PATTERN.matcher(text);
        if (bracketMatcher.find()) {
            findings.add("HIGH: Bracket-style instruction marker detected: " + bracketMatcher.group());
        }

        // 3. Check for markdown instruction markers
        var mdMatcher = MARKDOWN_INSTRUCTION_PATTERN.matcher(text);
        if (mdMatcher.find()) {
            findings.add("HIGH: Markdown instruction header detected: " +
                truncate(mdMatcher.group(), 60));
        }

        // 4. Check for imperative injection patterns
        var imperativeMatcher = IMPERATIVE_INJECTION_PATTERN.matcher(text);
        while (imperativeMatcher.find()) {
            findings.add("CRITICAL: Imperative injection pattern: '" +
                truncate(imperativeMatcher.group(), 60) + "'");
        }

        // 5. Check for exfiltration URLs
        var urlMatcher = EXFILTRATION_URL_PATTERN.matcher(text);
        while (urlMatcher.find()) {
            findings.add("CRITICAL: Exfiltration URL detected: " + urlMatcher.group());
        }

        // 6. Check for credential leaks
        var credMatcher = CREDENTIAL_PATTERN.matcher(text);
        while (credMatcher.find()) {
            findings.add("HIGH: Credential leak detected: " + truncate(credMatcher.group(), 40));
        }

        // Produce sanitized text: strip instruction tags and bracket markers
        String sanitized = sanitize(text);

        if (findings.isEmpty()) {
            return ScanResult.withSanitizedText(Severity.CLEAN, "No threats detected",
                findings, sanitized);
        }

        Severity severity = determineSeverity(findings);
        String threatDescription = findings.size() + " security finding(s) in tool response: " +
            String.join("; ", findings);

        log.warn("MCP response scan found {} issue(s): {}", findings.size(), threatDescription);
        return ScanResult.withSanitizedText(severity, threatDescription, findings, sanitized);
    }

    /**
     * Strips instruction injection tags and markers from text.
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text;
        // Remove XML-style instruction tags (full element)
        result = INSTRUCTION_TAG_PATTERN.matcher(result).replaceAll("[REDACTED]");
        // Remove self-closing instruction tags
        result = INSTRUCTION_TAG_SELF_CLOSING.matcher(result).replaceAll("[REDACTED]");
        // Remove bracket-style instruction markers
        result = BRACKET_INSTRUCTION_PATTERN.matcher(result).replaceAll("[REDACTED]");
        // Remove markdown instruction headers
        result = MARKDOWN_INSTRUCTION_PATTERN.matcher(result).replaceAll("[REDACTED]");
        // Redact exfiltration URLs
        result = EXFILTRATION_URL_PATTERN.matcher(result).replaceAll("[REDACTED-URL]");
        // Redact credentials
        result = CREDENTIAL_PATTERN.matcher(result).replaceAll("[REDACTED-CREDENTIAL]");
        return result;
    }

    private String extractTagName(String tagText) {
        // Extract the tag name from something like <system ...>...</system>
        int start = tagText.indexOf('<');
        if (start < 0) return "unknown";
        int end = tagText.indexOf('>', start);
        if (end < 0) return "unknown";
        String tagContent = tagText.substring(start + 1, end).trim();
        int spaceIdx = tagContent.indexOf(' ');
        return spaceIdx > 0 ? tagContent.substring(0, spaceIdx) : tagContent;
    }

    private Severity determineSeverity(List<String> findings) {
        boolean hasCritical = false;
        boolean hasHigh = false;
        for (String f : findings) {
            if (f.startsWith("CRITICAL")) hasCritical = true;
            else if (f.startsWith("HIGH")) hasHigh = true;
        }
        if (hasCritical) return Severity.CRITICAL;
        if (hasHigh) return Severity.HIGH;
        return Severity.MEDIUM;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}