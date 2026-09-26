package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.tool.ClarifyGatewayStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Hermes parity (clarify_gateway.py _coerce_text_response_detailed): map a
 * typed user reply to an accepted clarify answer, or null on rejection.
 * Numeric picks and exact/case-insensitive labels always resolve; multi-select
 * takes comma/space number or label lists and returns a JSON array string;
 * open-ended entries accept any text; anything else is free prose (the caller
 * routes it as a normal turn) — except out-of-range numbers, which are a
 * failed selection (keep the prompt armed for a retry).
 */
public final class ClarifyTextCoercer {

    private ClarifyTextCoercer() {
    }

    /**
     * Accepted value for the typed reply, or null when it is prose / an
     * invalid selection (caller distinguishes by shape via
     * {@link #looksLikeSelection}).
     */
    public static String coerce(ClarifyGatewayStore.PendingClarify entry, String response) {
        String text = response == null ? "" : response.trim();
        if (text.isEmpty()) {
            return null;
        }
        List<String> choices = entry.choices();
        if (choices == null || choices.isEmpty() || entry.acceptsCustomResponse()) {
            return text; // open-ended or explicit Other accepts any text
        }
        if (entry.multiSelect()) {
            return coerceMultiSelect(text, choices);
        }
        Integer idx = parseInt(text);
        if (idx != null) {
            int i = idx - 1;
            return i >= 0 && i < choices.size() ? choices.get(i) : null; // out-of-range = failed selection
        }
        String label = matchLabel(text, choices);
        if (label != null) {
            return label;
        }
        // "Other"-style free text on a choice prompt: the Telegram flow flips
        // the entry into text mode via the Other button, so plain prose here is
        // rejected (prose) and routed as a normal message by the caller.
        return null;
    }

    /** True when the reply is selection-shaped (bare int / numeric list / label list). */
    public static boolean looksLikeSelection(String text, List<String> choices) {
        String stripped = text == null ? "" : text.trim();
        if (stripped.isEmpty()) {
            return false;
        }
        if (parseInt(stripped) != null) {
            return true;
        }
        List<String> tokens = splitTokens(stripped);
        if (tokens == null) {
            return false;
        }
        int maxWords = 1;
        for (String c : choices) {
            maxWords = Math.max(maxWords, c == null ? 1 : c.trim().split("\\s+").length);
        }
        for (String token : tokens) {
            if (parseInt(token) == null && token.trim().split("\\s+").length > maxWords) {
                return false;
            }
        }
        return true;
    }

    private static String coerceMultiSelect(String text, List<String> choices) {
        List<String> selected = new ArrayList<>();
        List<String> tokens = splitTokens(text);
        List<String> effective = tokens != null ? tokens : List.of(text);
        for (String token : effective) {
            String label;
            Integer idx = parseInt(token);
            label = idx != null
                ? (idx - 1 >= 0 && idx - 1 < choices.size() ? choices.get(idx - 1) : null)
                : matchLabel(token, choices);
            if (label == null) {
                return null; // one bad token rejects the whole reply
            }
            if (!selected.contains(label)) {
                selected.add(label);
            }
        }
        if (selected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < selected.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(selected.get(i).replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    /** Case-insensitive label match ignoring the (Recommended) suffix. */
    private static String matchLabel(String text, List<String> choices) {
        String wanted = ClarifyTool.stripRecommended(text).toLowerCase();
        for (String choice : choices) {
            if (ClarifyTool.stripRecommended(choice).toLowerCase().equals(wanted)) {
                return choice.trim();
            }
        }
        return null;
    }

    /** Comma-separated tokens, or space-separated all-numeric tokens; else null. */
    private static List<String> splitTokens(String text) {
        String stripped = text.trim();
        if (stripped.contains(",")) {
            List<String> tokens = new ArrayList<>();
            for (String part : stripped.split(",")) {
                if (!part.isBlank()) {
                    tokens.add(part.trim());
                }
            }
            return tokens;
        }
        String[] parts = stripped.split("\\s+");
        if (parts.length > 1) {
            boolean allNumeric = true;
            for (String p : parts) {
                if (parseInt(p) == null) {
                    allNumeric = false;
                    break;
                }
            }
            if (allNumeric) {
                return List.of(parts);
            }
        }
        return null;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
