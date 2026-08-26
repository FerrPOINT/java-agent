package com.azhukov.agent.bot.streaming;

import java.util.List;

/**
 * Silence marker utilities — extracted from {@link StreamEditor}.
 *
 * <p>Hermes parity (gateway/response_filters.py):
 * LIVE_GATEWAY_SILENT_MARKERS = {[SILENT], SILENT, NO_REPLY, "NO REPLY"}.
 * Canonicalisation, edge-punct stripping, 64-char cap.
 */
public final class SilenceMarkerUtils {

    private SilenceMarkerUtils() { }

    /**
     * Check if text ends with a partial silence marker prefix. If we render these,
     * the user sees "NO" / "NO_R" / "NO_RE" flash before the complete marker is scrubbed.
     * Hermes holdback: wait for the next chunk if text ends with a silence-marker prefix.
     */
    public static boolean endsWithPartialSilenceMarker(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        // NO_REPLY prefixes
        if (t.endsWith("NO") || t.endsWith("NO_") || t.endsWith("NO_R") || t.endsWith("NO_RE")
            || t.endsWith("NO_REPL") || t.endsWith("NO_REPLY")) return true;
        // "NO REPLY" (space form) prefixes
        if (t.endsWith("NO REP") || t.endsWith("NO REPL") || t.endsWith("NO REPLY")) return true;
        // [SILENT] and bare SILENT prefixes
        if (t.endsWith("[") || t.endsWith("[S") || t.endsWith("[SI") || t.endsWith("[SIL")
            || t.endsWith("[SILE") || t.endsWith("[SILEN") || t.endsWith("[SILENT")) return true;
        if (t.endsWith("SIL") || t.endsWith("SILE") || t.endsWith("SILEN") || t.endsWith("SILENT")) return true;
        return t.endsWith("***") || t.endsWith("**");
    }

    /**
     * Check if text is an intentional silence marker (Hermes parity: response_filters.py
     * is_intentional_silence_response). Canonicalises (upper-case, collapse whitespace),
     * strips edge punctuation while keeping brackets structural, caps length at 64 chars —
     * substantive prose that merely MENTIONS a marker is never suppressed.
     */
    public static boolean isSilenceMarker(String text) {
        if (text == null || text.isBlank()) return false;
        String stripped = text.strip();
        if (stripped.isEmpty()) return false;
        if (stripped.length() > 64) return false;
        // Candidate 1: canonical form as-is; Candidate 2: edge punctuation stripped
        // (".NO_REPLY" / "*NO_REPLY*"), keeping square brackets structural.
        for (String candidate : silenceCandidates(stripped)) {
            switch (candidate) {
                case "[SILENT]", "SILENT", "NO_REPLY", "NO REPLY" -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    /** Canonicalise: upper-case + collapse internal whitespace (Hermes _canonical_silence_candidate). */
    static String canonicalSilence(String text) {
        return String.join(" ", text.toUpperCase().strip().split("\\s+"));
    }

    /** Hermes _canonical_silence_candidates: raw canonical + edge-punctuation-stripped variant. */
    static List<String> silenceCandidates(String stripped) {
        String strippedPunct = stripEdgeSilencePunctuation(stripped);
        if (strippedPunct.equals(stripped)) {
            return List.of(canonicalSilence(stripped));
        }
        return List.of(canonicalSilence(stripped), canonicalSilence(strippedPunct));
    }

    /** Strip stray edge punctuation without erasing marker structure (brackets stay). */
    static String stripEdgeSilencePunctuation(String text) {
        int start = 0, end = text.length();
        while (start < end && text.charAt(start) != '[' && text.charAt(start) != ']'
            && isPunctuation(text.charAt(start))) {
            start++;
        }
        while (end > start && text.charAt(end - 1) != '[' && text.charAt(end - 1) != ']'
            && isPunctuation(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end).strip();
    }

    static boolean isPunctuation(char c) {
        int t = Character.getType(c);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION
            || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
            || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
            || t == Character.OTHER_PUNCTUATION;
    }
}