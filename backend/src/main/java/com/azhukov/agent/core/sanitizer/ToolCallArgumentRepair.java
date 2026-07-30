package com.azhukov.agent.core.sanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * Repairs malformed JSON in tool_call arguments.
 * <p>
 * Mirrors Hermes' agent\/message_sanitization.py (_repair_tool_call_arguments).
 * Models like GLM-5.1 via Ollama can produce truncated JSON, trailing commas,
 * Python None, unescaped control chars, etc. This class applies common repairs;
 * if all fail it returns "{}" so the request succeeds (better than crashing the session).
 */
@Slf4j
public class ToolCallArgumentRepair {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",\\s*([}\\]])");

    /**
     * Attempt to repair malformed tool_call argument JSON.
     *
     * @param rawArgs  the raw argument string from the model
     * @param toolName the tool name for logging
     * @return repaired JSON string, or "{}" if unrepairable
     */
    public String repair(String rawArgs, String toolName) {
        String rawStripped = rawArgs != null ? rawArgs.strip() : "";

        // Fast-path: empty / whitespace-only → empty object
        if (rawStripped.isEmpty()) {
            log.warn("Sanitized empty tool_call arguments for {}", toolName);
            return "{}";
        }

        // Python-literal None → {}
        if ("None".equals(rawStripped)) {
            log.warn("Sanitized Python-None tool_call arguments for {}", toolName);
            return "{}";
        }

        // Repair pass 0: json.loads with non-strict parsing (handles control chars)
        try {
            JsonNode parsed = MAPPER.readTree(rawStripped);
            String reserialised = MAPPER.writeValueAsString(parsed);
            if (!reserialised.equals(rawStripped)) {
                log.warn("Repaired unescaped control chars in tool_call arguments for {}", toolName);
            }
            return reserialised;
        } catch (Exception e) {
            // Fall through to repair passes
        }

        // Attempt common JSON repairs
        String fixed = rawStripped;

        // 1. Strip trailing commas before } or ]
        fixed = TRAILING_COMMA_PATTERN.matcher(fixed).replaceAll("$1");

        // 2. Close unclosed structures
        int openCurly = countChar(fixed, '{') - countChar(fixed, '}');
        int openBracket = countChar(fixed, '[') - countChar(fixed, ']');
        if (openCurly > 0) {
            fixed = fixed + "}".repeat(openCurly);
        }
        if (openBracket > 0) {
            fixed = fixed + "]".repeat(openBracket);
        }

        // 3. Remove excess closing braces/brackets (bounded iterations)
        for (int i = 0; i < 50; i++) {
            try {
                MAPPER.readTree(fixed);
                break;
            } catch (Exception e) {
                if (fixed.endsWith("}") && countChar(fixed, '}') > countChar(fixed, '{')) {
                    fixed = fixed.substring(0, fixed.length() - 1);
                } else if (fixed.endsWith("]") && countChar(fixed, ']') > countChar(fixed, '[')) {
                    fixed = fixed.substring(0, fixed.length() - 1);
                } else {
                    break;
                }
            }
        }

        try {
            MAPPER.readTree(fixed);
            log.warn("Repaired malformed tool_call arguments for {}: {} → {}",
                toolName, truncate(rawStripped, 80), truncate(fixed, 80));
            return fixed;
        } catch (Exception e) {
            // Fall through
        }

        // Repair pass 4: escape unescaped control chars inside JSON strings
        String escaped = escapeInvalidCharsInJsonStrings(fixed);
        if (!escaped.equals(fixed)) {
            try {
                MAPPER.readTree(escaped);
                log.warn("Repaired control-char-laced tool_call arguments for {}", toolName);
                return escaped;
            } catch (Exception e2) {
                // Fall through
            }
        }

        // Last resort: replace with empty object
        log.warn("Unrepairable tool_call arguments for {} — replaced with empty object (was: {})",
            toolName, truncate(rawStripped, 80));
        return "{}";
    }

    /**
     * Convenience method with default tool name.
     */
    public String repair(String rawArgs) {
        return repair(rawArgs, "?");
    }

    /**
     * Escape unescaped control characters inside JSON string values.
     * Walks the raw JSON character-by-character, tracking whether we are inside
     * a double-quoted string. Inside strings, replaces literal control characters
     * (0x00-0x1F) that aren't already part of an escape sequence with their \\uXXXX equivalents.
     */
    static String escapeInvalidCharsInJsonStrings(String raw) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char ch = raw.charAt(i);
            if (inString) {
                if (ch == '\\' && i + 1 < n) {
                    out.append(ch);
                    out.append(raw.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                    out.append(ch);
                } else if (ch < 0x20) {
                    out.append(String.format("\\u%04x", (int) ch));
                } else {
                    out.append(ch);
                }
            } else {
                if (ch == '"') {
                    inString = true;
                }
                out.append(ch);
            }
            i++;
        }
        return out.toString();
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}