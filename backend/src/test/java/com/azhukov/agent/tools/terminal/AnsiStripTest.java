package com.azhukov.agent.tools.terminal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermes parity tests for AnsiStrip (tools/ansi_strip.py):
 * full ECMA-48 coverage — CSI private-mode, OSC, DCS, 8-bit C1.
 */
class AnsiStripTest {

    @Test
    void basicCsiStripped() {
        assertEquals("hello world", AnsiStrip.strip("\u001b[31mhello\u001b[0m world"));
    }

    @Test
    void privateModeCsiStripped() {
        // \u001b[?25l (hide cursor) — NOT covered by the old [0-9;]* regex
        assertEquals("done", AnsiStrip.strip("\u001b[?25ldone"));
        assertEquals("clean", AnsiStrip.strip("\u001b[?1049hclean\u001b[?1049l"));
    }

    @Test
    void oscWithTitleStripped() {
        // OSC with BEL terminator: window title
        assertEquals("ls -la", AnsiStrip.strip("\u001b]0;user@host: ~/project\u0007ls -la"));
        // OSC with ST terminator (ESC \)
        assertEquals("ok", AnsiStrip.strip("\u001b]2;title\u001b\\ok"));
    }

    @Test
    void colonSeparatedParamsStripped() {
        // CSI with colon-separated params (SGR 38:2:... truecolor)
        assertEquals("colored", AnsiStrip.strip("\u001b[38:2:255:0:0mcolored"));
    }

    @Test
    void eightBitC1Stripped() {
        // 8-bit CSI (0x9b) variant
        assertEquals("x", AnsiStrip.strip("\u009b31mx"));
    }

    @Test
    void plainTextUntouched() {
        assertEquals("plain text 123", AnsiStrip.strip("plain text 123"));
        assertNull(AnsiStrip.strip(null));
        assertEquals("", AnsiStrip.strip(""));
    }

    @Test
    void mixedContent() {
        String raw = "\u001b[1m\u001b]0;build\u0007Compiling\u001b[0m... \u001b[32m100%\u001b[0m";
        assertEquals("Compiling... 100%", AnsiStrip.strip(raw));
    }

    @Test
    void loneSequencesStripped() {
        assertEquals("", AnsiStrip.strip("\u001b[K"));
        assertEquals("", AnsiStrip.strip("\u001b[2J"));
        assertEquals("", AnsiStrip.strip("\u001b[?2004h"));
    }

    @Test
    void dcsStringsStrippedFixed() {
        // DCS ... terminated by ST (ESC \)
        assertEquals("after", AnsiStrip.strip("\u001bP1$r\u001b\\after"));
    }
}
