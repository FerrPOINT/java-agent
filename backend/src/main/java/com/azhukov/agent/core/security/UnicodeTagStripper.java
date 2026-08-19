package com.azhukov.agent.core.security;

import java.util.regex.Pattern;

/**
 * Strip invisible Unicode TAG characters (U+E0000–U+E007F) from text.
 * <p>
 * Tag characters are invisible in terminals and chat UIs but fully visible
 * to LLM tokenizers, making them a prompt-injection smuggling channel for
 * untrusted tool output (MCP servers, web content).
 * <p>
 * Valid emoji tag sequences (U+1F3F4 base + tag spec + U+E007F CANCEL TAG —
 * regional flags like Scotland/Wales) are preserved.
 * <p>
 * Ported from Hermes {@code tools/ansi_strip.py:strip_unicode_tags}.
 */
public final class UnicodeTagStripper {

    private UnicodeTagStripper() {}

    // Valid emoji tag sequence: U+1F3F4 + tag spec chars (U+E0020–U+E007E) + U+E007F CANCEL TAG
    private static final Pattern UNICODE_TAG_SUB = Pattern.compile(
        "(\\uD83C\\uDFF4[\\uE0020-\\uE007E]+\\uE007F)" // valid emoji tag seq (kept)
        + "|[\\uE0000-\\uE007F]"                       // any other tag char (stripped)
    );

    // Fast-path check — plane-14 tag chars only
    private static final Pattern HAS_UNICODE_TAG = Pattern.compile("[\\uE0000-\\uE007F]");

    /**
     * Remove invisible Unicode TAG characters from text.
     * Valid emoji tag sequences are preserved.
     *
     * @param text the text to strip; null returns null, empty returns empty
     * @return text with tag characters removed
     */
    public static String stripUnicodeTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!HAS_UNICODE_TAG.matcher(text).find()) {
            return text; // fast path — no tag chars
        }
        return UNICODE_TAG_SUB.matcher(text).replaceAll(m -> {
            String g1 = m.group(1);
            return g1 != null ? g1 : ""; // keep emoji seq, strip other tag chars
        });
    }
}