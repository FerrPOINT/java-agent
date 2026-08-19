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
    // NOTE: plane-14 codepoints are outside the BMP, so Java \\uXXXX escapes cannot
    // represent them. Using \\uE0000 in a char class silently degrades to the two
    // chars U+E000 and '0', corrupting the range and matching ordinary ASCII letters
    // (verified: it stripped the letter 'T' from plain text). Use \\x{...} instead.
    private static final Pattern UNICODE_TAG_SUB = Pattern.compile(
        "(\\x{1F3F4}[\\x{E0020}-\\x{E007E}]+\\x{E007F})"  // valid emoji tag seq (kept)
        + "|[\\x{E0000}-\\x{E007F}]"                     // any other tag char (stripped)
    );

    // Fast-path check — plane-14 tag chars only
    private static final Pattern HAS_UNICODE_TAG = Pattern.compile("[\\x{E0000}-\\x{E007F}]");

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