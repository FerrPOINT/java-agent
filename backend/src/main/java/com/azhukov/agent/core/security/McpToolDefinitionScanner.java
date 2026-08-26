package com.azhukov.agent.core.security;

import com.azhukov.agent.core.util.TextTruncator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scans MCP tool definitions (name, description, inputSchema) for:
 * <ul>
 *   <li>Hidden instructions in descriptions (imperative patterns)</li>
 *   <li>Invisible unicode characters (zero-width spaces, bidi overrides, TAG chars)</li>
 *   <li>Encoded payloads (base64-encoded strings that decode to instruction-like content)</li>
 *   <li>Schema abuse (suspicious required field names; instruction-bearing default values)</li>
 *   <li>Exfiltration URLs in descriptions (webhook.site, requestbin, pastebin, ngrok)</li>
 * </ul>
 *
 * Returns a {@link ScanResult} with severity and threat description.
 */
@Slf4j
@Component
public class McpToolDefinitionScanner {

    private final ObjectMapper objectMapper;

    public McpToolDefinitionScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Imperative / instruction patterns in tool descriptions ──────────────
    private static final Pattern IMPERATIVE_PATTERN = Pattern.compile(
        "(?i)(?:ignore\\s+previous|you\\s+are\\s+now|execute\\s+this|system\\s+prompt|" +
        "disregard\\s+(?:all\\s+|the\\s+)?(?:previous|above)|forget\\s+(?:your|all|previous)|" +
        "new\\s+instructions?:|you\\s+must\\s+now|act\\s+as\\s+(?:if\\s+you\\s+are\\s+)?a\\b|" +
        "from\\s+now\\s+on|override\\s+(?:your|the|all)|reveal\\s+your\\s+instructions|" +
        "show\\s+your\\s+system\\s+prompt|do\\s+not\\s+follow|jailbreak)"
    );

    // ── Exfiltration URL patterns ────────────────────────────────────────────
    private static final Pattern EXFILTRATION_URL_PATTERN = Pattern.compile(
        "(?i)https?://(?:[a-z0-9-]+\\.)*" +
        "(?:webhook\\.site|requestbin|requestb\\.in|pastebin\\.com|" +
        "ngrok\\.io|ngrok\\.app|ngrok-free\\.app|burpcollaborator\\.net|" +
        "hookbin\\.com|pipedream\\.net|beeceptor\\.com|mocky\\.io)"
    );

    // ── Suspicious required field names ─────────────────────────────────────
    private static final Pattern SUSPICIOUS_FIELD_NAME = Pattern.compile(
        "(?i)^(?:system_?prompt|override|command|exec|execute|eval|" +
        "instruction[s]?|system|secret|password|api_?key|token|auth)$"
    );

    // ── Instruction-bearing default value patterns in schema properties ─────
    private static final Pattern INSTRUCTION_IN_DEFAULT = Pattern.compile(
        "(?i)(?:ignore\\s+previous|you\\s+are\\s+now|execute\\s+this|system\\s+prompt|" +
        "disregard|override|reveal\\s+your|jailbreak|new\\s+instructions)"
    );

    // ── Invisible Unicode characters ─────────────────────────────────────────
    // Zero-width spaces: U+200B–U+200D
    // Bidi overrides: U+202A–U+202E
    // TAG characters: U+E0000–U+E007F (outside BMP, requires \x{} syntax)
    private static final Pattern INVISIBLE_UNICODE = Pattern.compile(
        "[\\x{200B}-\\x{200D}\\x{202A}-\\x{202E}\\x{E0000}-\\x{E007F}]"
    );

    // ── Base64 pattern: sequences of 20+ base64 chars (long enough to encode a message) ──
    private static final Pattern BASE64_CANDIDATE = Pattern.compile(
        "[A-Za-z0-9+/]{20,}={0,2}"
    );

    // ── Patterns to detect in decoded base64 ───────────────────────────────
    private static final Pattern DECODED_INSTRUCTION = Pattern.compile(
        "(?i)(?:ignore\\s+previous|you\\s+are\\s+now|execute\\s+this|system\\s+prompt|" +
        "disregard|override|reveal\\s+your|jailbreak|act\\s+as)"
    );

    /**
     * Scans a single MCP tool definition for security threats.
     *
     * @param toolName      the tool name
     * @param description   the tool description (may be null)
     * @param inputSchema   the tool input schema as a Map (may be null)
     * @return scan result with severity and findings
     */
    public ScanResult scan(String toolName, String description, Map<String, Object> inputSchema) {
        List<String> findings = new ArrayList<>();

        String desc = description == null ? "" : description;
        String name = toolName == null ? "" : toolName;
        Map<String, Object> schema = inputSchema != null ? inputSchema : Map.of();

        // 1. Scan description for imperative/instruction patterns
        scanImperativePatterns(name, desc, findings);

        // 2. Scan for invisible unicode characters
        scanInvisibleUnicode(name, desc, findings);

        // 3. Scan for encoded payloads (base64)
        scanEncodedPayloads(name, desc, findings);

        // 4. Scan schema for abuse
        scanSchemaAbuse(name, schema, findings);

        // 5. Scan for exfiltration URLs
        scanExfiltrationUrls(name, desc, findings);

        // Also scan description for invisible unicode in the tool name
        scanInvisibleUnicodeInName(name, findings);

        if (findings.isEmpty()) {
            return ScanResult.clean();
        }

        Severity severity = determineSeverity(findings);
        String threatDescription = buildThreatDescription(toolName, findings);

        log.warn("MCP tool definition scan [{}] found {} issue(s): {}", toolName, findings.size(), threatDescription);
        return ScanResult.of(severity, threatDescription, findings);
    }

    private void scanImperativePatterns(String toolName, String description, List<String> findings) {
        var matcher = IMPERATIVE_PATTERN.matcher(description);
        while (matcher.find()) {
            findings.add("HIGH: Imperative instruction pattern in description: '" + matcher.group() + "'");
        }
    }

    private void scanInvisibleUnicode(String toolName, String description, List<String> findings) {
        var matcher = INVISIBLE_UNICODE.matcher(description);
        if (matcher.find()) {
            findings.add("HIGH: Invisible Unicode character(s) detected in description");
        }
    }

    private void scanInvisibleUnicodeInName(String toolName, List<String> findings) {
        var matcher = INVISIBLE_UNICODE.matcher(toolName);
        if (matcher.find()) {
            findings.add("HIGH: Invisible Unicode character(s) detected in tool name");
        }
    }

    private void scanEncodedPayloads(String toolName, String description, List<String> findings) {
        var matcher = BASE64_CANDIDATE.matcher(description);
        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                byte[] decoded = Base64.getDecoder().decode(candidate);
                String decodedStr = new String(decoded, StandardCharsets.UTF_8);
                if (DECODED_INSTRUCTION.matcher(decodedStr).find()) {
                    findings.add("CRITICAL: Base64-encoded instruction payload in description: '" +
                        TextTruncator.truncate(candidate, 60) + "' decodes to instruction-like content");
                }
            } catch (IllegalArgumentException e) {
                // Not valid base64, skip
            }
        }
    }

    private void scanExfiltrationUrls(String toolName, String description, List<String> findings) {
        var matcher = EXFILTRATION_URL_PATTERN.matcher(description);
        while (matcher.find()) {
            findings.add("CRITICAL: Exfiltration URL detected in description: " + matcher.group());
        }
    }

    @SuppressWarnings("unchecked")
    private void scanSchemaAbuse(String toolName, Map<String, Object> schema, List<String> findings) {
        // Check required field names
        Object requiredObj = schema.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object req : requiredList) {
                String reqName = String.valueOf(req);
                if (SUSPICIOUS_FIELD_NAME.matcher(reqName).matches()) {
                    findings.add("HIGH: Suspicious required field name in schema: '" + reqName + "'");
                }
            }
        }

        // Check properties for instruction-bearing default values
        Object propsObj = schema.get("properties");
        if (propsObj instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> entry : props.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                // Check for suspicious property names
                if (SUSPICIOUS_FIELD_NAME.matcher(propName).matches()) {
                    findings.add("MEDIUM: Suspicious property name in schema: '" + propName + "'");
                }
                // Check default values for instructions
                if (entry.getValue() instanceof Map<?, ?> propDef) {
                    Object defaultValue = propDef.get("default");
                    if (defaultValue != null) {
                        String defaultStr = String.valueOf(defaultValue);
                        if (INSTRUCTION_IN_DEFAULT.matcher(defaultStr).find()) {
                            findings.add("CRITICAL: Instruction-bearing default value in schema property '" +
                                propName + "': " + TextTruncator.truncate(defaultStr, 80));
                        }
                    }
                    // Check description in schema property
                    Object propDesc = propDef.get("description");
                    if (propDesc != null) {
                        String propDescStr = String.valueOf(propDesc);
                        if (IMPERATIVE_PATTERN.matcher(propDescStr).find()) {
                            findings.add("HIGH: Instruction pattern in schema property description '" +
                                propName + "'");
                        }
                    }
                }
            }
        }
    }

    private Severity determineSeverity(List<String> findings) {
        boolean hasCritical = false;
        boolean hasHigh = false;
        boolean hasMedium = false;
        boolean hasLow = false;
        for (String f : findings) {
            if (f.startsWith("CRITICAL")) hasCritical = true;
            else if (f.startsWith("HIGH")) hasHigh = true;
            else if (f.startsWith("MEDIUM")) hasMedium = true;
            else if (f.startsWith("LOW")) hasLow = true;
        }
        if (hasCritical) return Severity.CRITICAL;
        if (hasHigh) return Severity.HIGH;
        if (hasMedium) return Severity.MEDIUM;
        if (hasLow) return Severity.LOW;
        return Severity.LOW;
    }

    private String buildThreatDescription(String toolName, List<String> findings) {
        return "Tool '" + toolName + "' has " + findings.size() +
            " security finding(s): " + String.join("; ", findings);
    }

}