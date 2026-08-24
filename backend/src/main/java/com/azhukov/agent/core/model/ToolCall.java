package com.azhukov.agent.core.model;

import java.util.Objects;

public record ToolCall(
    String id,
    String name,
    String arguments
) {
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
}
