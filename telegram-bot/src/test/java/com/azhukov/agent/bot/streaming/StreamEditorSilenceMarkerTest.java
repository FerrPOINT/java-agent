package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermes parity tests for silence markers (gateway/response_filters.py):
 * LIVE_GATEWAY_SILENT_MARKERS = {[SILENT], SILENT, NO_REPLY, "NO REPLY"},
 * canonicalisation, edge-punct stripping, 64-char cap.
 */
@ExtendWith(MockitoExtension.class)
class StreamEditorSilenceMarkerTest {

    @Mock
    private com.azhukov.agent.bot.client.TelegramClient client;

    private StreamEditor editor;

    @BeforeEach
    void setUp() {
        var props = new com.azhukov.agent.bot.config.BotProperties();
        editor = new StreamEditor(client, props, new com.azhukov.agent.bot.media.MediaDeliveryService(), new com.azhukov.agent.bot.rich.RichMessageSupport(client));
    }

    private boolean isSilenceMarker(String text) throws Exception {
        Method m = StreamEditor.class.getDeclaredMethod("isSilenceMarker", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(editor, text);
    }

    private boolean endsWithPartial(String text) throws Exception {
        Method m = StreamEditor.class.getDeclaredMethod("endsWithPartialSilenceMarker", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(editor, text);
    }

    @Test
    void exactMarkersSuppressed() throws Exception {
        assertTrue(isSilenceMarker("NO_REPLY"));
        assertTrue(isSilenceMarker("[SILENT]"));
        assertTrue(isSilenceMarker("SILENT"), "bare SILENT is in the Hermes marker set");
        assertTrue(isSilenceMarker("NO REPLY"), "spaced NO REPLY is in the Hermes marker set");
    }

    @Test
    void canonicalisation() throws Exception {
        assertTrue(isSilenceMarker("  no_reply  "), "whitespace trimmed + upper-cased");
        assertTrue(isSilenceMarker("no reply"), "case-insensitive + canonical whitespace");
        assertTrue(isSilenceMarker("[silent]"));
    }

    @Test
    void edgePunctuationStripped() throws Exception {
        assertTrue(isSilenceMarker(".NO_REPLY"), "leading period stripped");
        assertTrue(isSilenceMarker("*NO_REPLY*"), "wrapping asterisks stripped");
        assertTrue(isSilenceMarker("[SILENT]."), "trailing period stripped");
    }

    @Test
    void bracketsStayStructural() throws Exception {
        // "[SILENT" must NOT become "SILENT" via punctuation stripping of '['
        assertFalse(isSilenceMarker("[SILENT"), "malformed bracket must not canonicalise to SILENT");
    }

    @Test
    void proseNeverSuppressed() throws Exception {
        assertFalse(isSilenceMarker("NO_REPLY is the marker the gateway suppresses"));
        assertFalse(isSilenceMarker("The system stays SILENT when idle"));
        assertFalse(isSilenceMarker("I will not reply to that. NO_REPLY said the docs, and the model obeyed the rule about markers in long prose."));
    }

    @Test
    void lengthCap() throws Exception {
        String longText = "NO_REPLY ".repeat(20).strip();
        assertTrue(longText.length() > 64);
        assertFalse(isSilenceMarker(longText), ">64 chars is prose, not a marker");
    }

    @Test
    void blankIsNotSilence() throws Exception {
        assertFalse(isSilenceMarker(""));
        assertFalse(isSilenceMarker("   "));
        assertFalse(isSilenceMarker(null));
    }

    @Test
    void legacyTripleStarKept() throws Exception {
        // *** was in the old java set; Hermes does not have it — parity means NOT a marker.
        // But the old code suppressed it; keeping parity with Hermes over legacy behaviour.
        assertFalse(isSilenceMarker("***"));
    }

    @Test
    void partialPrefixesHeldBack() throws Exception {
        assertTrue(endsWithPartial("NO"));
        assertTrue(endsWithPartial("NO_"));
        assertTrue(endsWithPartial("NO_REPL"));
        assertTrue(endsWithPartial("[SILE"));
        assertTrue(endsWithPartial("SILE"), "bare SILENT prefixes also held back");
        assertTrue(endsWithPartial("SILEN"));
        assertTrue(endsWithPartial("NO REP"), "spaced form prefixes held back");
        assertTrue(endsWithPartial("NO REPLY"));
    }

    @Test
    void normalTextNotHeldBack() throws Exception {
        assertFalse(endsWithPartial("Here is the answer: 42"));
        assertFalse(endsWithPartial("Команда выполнена"));
    }
}
