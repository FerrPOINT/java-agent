package com.azhukov.agent.tools.terminal;

import java.util.regex.Pattern;

/**
 * Strips ANSI escape sequences from subprocess output (Hermes parity: tools/ansi_strip.py).
 *
 * <p>Prevents ANSI codes from entering the model's context — the root cause of models
 * copying escape sequences into file writes. Covers the full ECMA-48 spec:</p>
 * <ul>
 *   <li>CSI sequences (including private-mode {@code ?} prefix, colon-separated params,
 *       intermediate bytes)</li>
 *   <li>OSC strings (BEL and ST terminators)</li>
 *   <li>DCS/SOS/PM/APC string sequences</li>
 *   <li>nF multi-byte escapes, Fp/Fe/Fs single-byte escapes</li>
 *   <li>8-bit C1 control characters (including 8-bit CSI/OSC)</li>
 * </ul>
 */
public final class AnsiStrip {

    private AnsiStrip() {
    }

    /**
     * Full ECMA-48 escape-sequence pattern. Mirrors Hermes {@code _ANSI_ESCAPE_RE}:
     * escape byte followed by CSI / OSC / DCS-family / nF / single-byte forms,
     * plus the 8-bit C1 variants.
     */
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
        "\\x1b"
            + "(?:"
            + "\\[[\\x30-\\x3f]*[\\x20-\\x2f]*[\\x40-\\x7e]"     // CSI sequence
            + "|\\][\\s\\S]*?(?:\\x07|\\x1b\\\\)"                  // OSC (BEL or ST terminator)
            + "|[PX^_][\\s\\S]*?(?:\\x1b\\\\)"                     // DCS/SOS/PM/APC strings
            + "|[\\x20-\\x2f]+[\\x30-\\x7e]"                       // nF escape sequences
            + "|[\\x30-\\x7e]"                                      // Fp/Fe/Fs single-byte
            + ")"
            + "|\\x9b[\\x30-\\x3f]*[\\x20-\\x2f]*[\\x40-\\x7e]"      // 8-bit CSI
            + "|\\x9d[\\s\\S]*?(?:\\x07|\\x9c)"                      // 8-bit OSC
            + "|[\\x80-\\x9f]"                                       // Other 8-bit C1 controls
    );

    /** Fast-path check — skip full regex when no escape-like bytes are present (Hermes _HAS_ESCAPE). */
    private static final Pattern HAS_ESCAPE = Pattern.compile("[\\x1b\\x80-\\x9f]");

    /**
     * Strip all ANSI escape sequences from {@code text}.
     *
     * @param text raw subprocess output
     * @return output with escape sequences removed; {@code null} passes through unchanged
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!HAS_ESCAPE.matcher(text).find()) {
            return text;
        }
        return ANSI_ESCAPE.matcher(text).replaceAll("");
    }
}
