package com.azhukov.agent.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A tool call with every provider identifier retained for history pairing.
 *
 * <p>Responses-compatible providers can expose the pairing id ({@code callId})
 * separately from the response item id. Bridges can also serialize both as a
 * {@code callId|responseItemId} composite. These spellings identify one call,
 * not different calls.</p>
 */
public record ToolCall(
    String id,
    String callId,
    String responseItemId,
    String name,
    String arguments
) {
    /** Compatibility constructor for Chat Completions and existing callers. */
    public ToolCall(String id, String name, String arguments) {
        this(id, null, null, name, arguments);
    }

    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
    }

    /** Returns the provider pairing key used for outbound tool results. */
    public String pairingId() {
        String candidate = nonBlank(callId) ? callId : id;
        if (candidate == null) {
            return "";
        }
        int separator = candidate.indexOf('|');
        String canonical = separator >= 0 ? candidate.substring(0, separator) : candidate;
        return canonical.strip();
    }

    /** Returns every wire spelling that may identify this call. */
    public Set<String> idVariants() {
        return expandIdVariants(id, callId, responseItemId);
    }

    /** Returns every wire spelling carried by a tool-result id. */
    public static Set<String> resultIdVariants(String toolCallId) {
        return expandIdVariants(toolCallId);
    }

    /** Replaces only the pairing half while preserving the Responses item id. */
    public ToolCall withPairingId(String replacement) {
        String normalized = replacement == null ? "" : replacement.strip();
        String nextId;
        String nextCallId = null;
        if (nonBlank(callId)) {
            nextCallId = normalized;
            // The legacy id field may still carry a composite spelling; keep
            // only its response-item half when the pairing moves to callId.
            nextId = nonBlank(responseItemId) ? responseItemId
                : (id != null && id.contains("|") ? id.substring(id.indexOf('|') + 1) : null);
            if (!nonBlank(nextId)) {
                nextId = null;
            }
        } else if (id != null && id.contains("|")) {
            nextId = normalized + id.substring(id.indexOf('|'));
        } else {
            nextId = normalized;
        }
        return new ToolCall(nextId, nextCallId, responseItemId, name, arguments);
    }

    private static Set<String> expandIdVariants(String... values) {
        Set<String> variants = new LinkedHashSet<>();
        for (String raw : values) {
            if (!nonBlank(raw)) {
                continue;
            }
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            variants.add(value);
            for (String part : value.split("\\|")) {
                String stripped = part.strip();
                if (!stripped.isEmpty()) {
                    variants.add(stripped);
                }
            }
        }
        return Set.copyOf(variants);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** Deterministic fallback for providers that omit a tool-call id. */
    public static String deterministicCallId(String fnName, String arguments, int index) {
        String seed = fnName + ":" + arguments + ":" + index;
        return "call_" + sha256_12(seed);
    }

    private static String sha256_12(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
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
