package com.azhukov.agent.core.util;

import java.util.Locale;

/**
 * WP-1 (Hermes gateway/response_filters.py is_autonomous_silence_response):
 * silence matcher for autonomous lanes (cron, webhook).
 *
 * <p>Suppresses delivery when a silence marker is (a) the whole response,
 * (b) on its own first or last line (a short note on a separate line), or
 * (c) the bracketed sentinel {@code [SILENT]} opens the response as a
 * same-line prefix — the documented {@code [SILENT] No changes detected}
 * pattern. A marker buried mid-sentence in a genuine report is still
 * delivered. This is the single shared matcher for BOTH the producer-side
 * enqueue gate and any future consumer-side check, so the two lanes can
 * never drift.</p>
 */
public final class CronSilenceFilter {

    private CronSilenceFilter() {
    }

    /** Hermes LIVE_GATEWAY_SILENT_MARKERS. */
    private static final String[] MARKERS = {"[SILENT]", "SILENT", "NO_REPLY", "NO REPLY"};

    /** Hermes _canonical_silence_candidate: upper-case + collapse whitespace. */
    static String canonical(String text) {
        return String.join(" ", text.strip().toUpperCase(Locale.ROOT).split("\\s+"));
    }

    private static boolean isToken(String line) {
        String c = canonical(line);
        for (String m : MARKERS) {
            if (m.equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the cron/webhook tick produced nothing worth a human's
     * attention. Mirrors is_autonomous_silence_response exactly; a blank
     * response is silent too (producer treats it as "nothing to deliver").
     */
    public static boolean isSilent(String response) {
        if (response == null) {
            return true;
        }
        String stripped = response.strip();
        if (stripped.isEmpty()) {
            return true;
        }
        // (a) whole response is exactly a token
        if (isToken(stripped)) {
            return true;
        }
        // (b) marker on its own first or last line
        String[] lines = stripped.split("\\n");
        String first = null;
        String last = null;
        for (String ln : lines) {
            if (!ln.isBlank()) {
                if (first == null) {
                    first = ln;
                }
                last = ln;
            }
        }
        if (first != null && (isToken(first) || isToken(last))) {
            return true;
        }
        // (c) bracketed sentinel as a same-line prefix ("[SILENT] No changes detected")
        return stripped.toUpperCase(Locale.ROOT).startsWith("[SILENT]");
    }
}
