package com.azhukov.agent.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    /** PR-3 parity: static spellings for a whole call (null-safe). */
    public static Set<String> idVariants(ToolCall toolCall) {
        return toolCall == null ? Set.of() : toolCall.idVariants();
    }

    /** PR-3 parity: static spellings of a raw id (null-safe). */
    public static Set<String> idVariants(String rawId) {
        return rawId == null ? Set.of() : expandIdVariants(rawId);
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
    private static final int RESPONSES_ID_MAX_LENGTH = 64;

    /**
     * Hermes parity (codex_responses_adapter.py): replayed Responses
     * function_call ids must be canonical pairing ids and must not exceed
     * the upstream 64-character cap.
     */
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

    private static String sha256_32(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
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
