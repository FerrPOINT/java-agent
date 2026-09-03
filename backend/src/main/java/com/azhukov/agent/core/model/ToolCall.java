package com.azhukov.agent.core.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ToolCall(
    String id,
    String name,
    String arguments
) {
    private static final int RESPONSES_ID_MAX_LENGTH = 64;

    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
    }

    /**
     * Hermes parity (message_sanitization.py:527): deterministic call_id
     * from tool call content. Used when the API doesn't provide a call_id —
     * random UUIDs would break OpenAI prompt-cache prefixes.
     */
    public static String deterministicCallId(String fnName, String arguments, int index) {
        String seed = fnName + ":" + arguments + ":" + index;
        String digest = sha256_12(seed);
        return "call_" + digest;
    }

    /**
     * Hermes parity (codex_responses_adapter.py): replayed Responses
     * function_call ids must be canonical pairing ids and must not exceed
     * the upstream 64-character cap.
     */
    public static String responsesCallId(String rawId, String fnName, String arguments, int index) {
        String value = splitResponsesCallId(rawId);
        if (value.isBlank()) {
            value = deterministicCallId(fnName, arguments, index);
        } else if (value.startsWith("fc_") && value.length() > 3) {
            value = "call_" + value.substring(3);
        }
        if (value.length() <= RESPONSES_ID_MAX_LENGTH) {
            return value;
        }
        return "call_" + sha256_32(value);
    }

    /**
     * Hermes parity: sanitize only replayed function_call names, never live
     * tool definitions whose names must still match the dispatch registry.
     */
    public static String sanitizeReplayedFunctionName(String name) {
        if (name == null) {
            return "fn";
        }
        if (name.matches("[A-Za-z0-9_-]{1,64}")) {
            return name;
        }
        String coerced = name.strip()
            .replaceAll("[^A-Za-z0-9_-]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
        if (coerced.isBlank()) {
            return "fn";
        }
        return coerced.length() <= RESPONSES_ID_MAX_LENGTH
            ? coerced
            : coerced.substring(0, RESPONSES_ID_MAX_LENGTH);
    }

    /**
     * Hermes parity (message_sanitization.py:566): a Responses/Codex bridge
     * can carry both the pairing id and response-item id as "call|item".
     * Treat each non-blank component as an alias for the same tool call.
     */
    public static Set<String> idVariants(ToolCall toolCall) {
        return toolCall == null ? Set.of() : idVariants(toolCall.id());
    }

    public static Set<String> idVariants(String rawId) {
        if (rawId == null) {
            return Set.of();
        }
        String value = rawId.strip();
        if (value.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(value);
        if (value.indexOf('|') >= 0) {
            for (String part : value.split("\\|")) {
                String trimmed = part.strip();
                if (!trimmed.isEmpty()) {
                    variants.add(trimmed);
                }
            }
        }
        return Set.copyOf(variants);
    }

    /**
     * Drop later tool calls in the same assistant turn that share any pairing
     * id alias with an earlier call. Strict providers reject duplicate ids,
     * and a later duplicate cannot be paired losslessly on replay.
     */
    public static List<ToolCall> deduplicateByIdVariants(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.size() < 2) {
            return toolCalls;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ToolCall> kept = new ArrayList<>(toolCalls.size());
        boolean changed = false;
        for (ToolCall toolCall : toolCalls) {
            Set<String> variants = idVariants(toolCall);
            if (!variants.isEmpty() && variants.stream().anyMatch(seen::contains)) {
                changed = true;
                continue;
            }
            kept.add(toolCall);
            seen.addAll(variants);
        }
        return changed ? List.copyOf(kept) : toolCalls;
    }

    private static String sha256_12(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String splitResponsesCallId(String rawId) {
        if (rawId == null) {
            return "";
        }
        String value = rawId.strip();
        if (value.isEmpty()) {
            return "";
        }
        int separator = value.indexOf('|');
        if (separator < 0) {
            return value;
        }
        String callId = value.substring(0, separator).strip();
        if (!callId.isEmpty()) {
            return callId;
        }
        return value.substring(separator + 1).strip();
    }

    private static String sha256_32(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
