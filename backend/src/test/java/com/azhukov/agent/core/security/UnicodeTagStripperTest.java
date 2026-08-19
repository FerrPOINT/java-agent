package com.azhukov.agent.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for UnicodeTagStripper.
 *
 * Bug history: the plane-14 tag range was written with {@code \\uE0000-\\uE007F}
 * escapes. Java {@code \\uXXXX} escapes cannot represent non-BMP codepoints, so
 * the pattern silently degraded to chars U+E000 and '0' — corrupting the char
 * class so it matched ordinary ASCII letters and stripped them from MCP tool
 * output (e.g. "TextContent[...text=ok...]" became ", , "). These tests pin the
 * correct behaviour: plain ASCII passes through untouched.
 */
class UnicodeTagStripperTest {

    @Test
    void plainAsciiPassesThroughUntouched() {
        String s = "TextContent[annotations=null, text=ok, meta=null]";
        assertThat(UnicodeTagStripper.stripUnicodeTags(s)).isEqualTo(s);
    }

    @Test
    void emptyAndNullAreHandled() {
        assertThat(UnicodeTagStripper.stripUnicodeTags(null)).isNull();
        assertThat(UnicodeTagStripper.stripUnicodeTags("")).isEmpty();
    }

    @Test
    void stripsLoneTagCharacters() {
        // U+E0041 = TAG LATIN CAPITAL LETTER A (plane-14 tag char, invisible)
        // Correct surrogate pair for U+E0041: high U+DB40, low U+DC41
        String tagged = "hello \uDB40\uDC41 world";
        String result = UnicodeTagStripper.stripUnicodeTags(tagged);
        assertThat(result).isEqualTo("hello  world");
        assertThat(result).contains("hello");
        assertThat(result).doesNotContain("\uDB40\uDC41");
    }

    @Test
    void preservesValidEmojiTagSequence() {
        // U+1F3F4 (black flag) + U+E0047 (TAG G) + U+E0042 (TAG B) + U+E0047 (TAG SCT...) + U+E007F (CANCEL)
        // Scotland flag: 1F3F4 E0067 E0062 E0073 E0063 E0074 E007F
        String flag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F";
        String result = UnicodeTagStripper.stripUnicodeTags(flag + " keep");
        assertThat(result).contains(flag);
        assertThat(result).endsWith(" keep");
    }

    @Test
    void regularEmojiAndCyrillicAreUntouched() {
        String s = "Привет 👍 мир — обычный текст";
        assertThat(UnicodeTagStripper.stripUnicodeTags(s)).isEqualTo(s);
    }
}
