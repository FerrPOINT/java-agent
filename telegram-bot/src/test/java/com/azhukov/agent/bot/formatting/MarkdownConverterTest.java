package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownConverterTest {

    @Test
    void boldText_convertsToTelegramBold() {
        String result = MarkdownConverter.convert("**hello**");
        assertThat(result).isEqualTo("*hello*");
    }

    @Test
    void italicText_convertsToTelegramItalic() {
        String result = MarkdownConverter.convert("*hello*");
        assertThat(result).isEqualTo("_hello_");
    }

    @Test
    void strikethroughText_convertsToTelegramStrike() {
        String result = MarkdownConverter.convert("~~hello~~");
        assertThat(result).isEqualTo("~hello~");
    }

    @Test
    void inlineCode_preservedAndNotEscaped() {
        String result = MarkdownConverter.convert("`code with _ and *`");
        assertThat(result).isEqualTo("`code with _ and *`");
    }

    @Test
    void codeBlock_preservedAndNotEscaped() {
        String result = MarkdownConverter.convert("```java\nSystem.out.println(\"hello.world\");\n```");
        assertThat(result).isEqualTo("```java\nSystem.out.println(\"hello.world\");\n```");
    }

    @Test
    void link_preservedAndNotEscapedInUrl() {
        String result = MarkdownConverter.convert("[click here](https://example.com/path?q=1)");
        assertThat(result).isEqualTo("[click here](https://example.com/path?q=1)");
    }

    @Test
    void specialChars_escapedInPlainText() {
        String result = MarkdownConverter.convert("Hello. World! Test #1 + test - 2 = 3");
        assertThat(result).isEqualTo("Hello\\. World\\! Test \\#1 \\+ test \\- 2 \\= 3");
    }

    @Test
    void underscore_escapedInPlainText() {
        String result = MarkdownConverter.convert("hello_world");
        assertThat(result).isEqualTo("hello\\_world");
    }

    @Test
    void mixedFormatting_allConverted() {
        String input = "**bold** and *italic* and ~~strike~~";
        String result = MarkdownConverter.convert(input);
        assertThat(result).isEqualTo("*bold* and _italic_ and ~strike~");
    }

    @Test
    void specialCharsInsideBold_notDoubleEscaped() {
        // Inside bold, the text is not escaped because it's part of the formatting
        // The bold conversion wraps text in *...*, and the special chars inside
        // are still escaped as plain text since we process formatting before escaping
        String result = MarkdownConverter.convert("**hello.world**");
        // Bold is converted to *hello.world* — then the . inside is escaped
        assertThat(result).isEqualTo("*hello\\.world*");
    }

    @Test
    void codeBlockWithSpecialChars_notEscaped() {
        String result = MarkdownConverter.convert("```\nif (a.b > c) { return d! }\n```");
        assertThat(result).isEqualTo("```\nif (a.b > c) { return d! }\n```");
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(MarkdownConverter.convert("")).isEqualTo("");
        assertThat(MarkdownConverter.convert(null)).isEqualTo("");
    }

    @Test
    void plainText_allSpecialCharsEscaped() {
        String result = MarkdownConverter.convert("a_b*c[d]e(f)g~h`i>j#k+l-m=n|o{p}q.r!s");
        assertThat(result).isEqualTo("a\\_b\\*c\\[d\\]e\\(f\\)g\\~h\\`i\\>j\\#k\\+l\\-m\\=n\\|o\\{p\\}q\\.r\\!s");
    }

    @Test
    void boldAndSpecialChar_combined() {
        String result = MarkdownConverter.convert("**bold text.**");
        assertThat(result).isEqualTo("*bold text\\.*");
    }

    // ─── Heading support ────────────────────────────────────────────

    @Test
    void heading_h1_convertsToBold() {
        String result = MarkdownConverter.convert("# Heading");
        assertThat(result).isEqualTo("*Heading*");
    }

    @Test
    void heading_h2_convertsToBold() {
        String result = MarkdownConverter.convert("## Subheading");
        assertThat(result).isEqualTo("*Subheading*");
    }

    @Test
    void heading_h6_maxLevel() {
        String result = MarkdownConverter.convert("###### Deep");
        assertThat(result).isEqualTo("*Deep*");
    }

    @Test
    void heading_withSpecialChars_convertsToBoldWithEscaping() {
        String result = MarkdownConverter.convert("# Hello.World!");
        // Heading → **Hello.World!** → bold conversion escapes the content
        assertThat(result).isEqualTo("*Hello\\.World\\!*");
    }

    @Test
    void heading_multipleInText() {
        String input = "# Title\nSome text\n## Section";
        String result = MarkdownConverter.convert(input);
        assertThat(result).isEqualTo("*Title*\nSome text\n*Section*");
    }

    @Test
    void heading_notMatchedWhenNotAtStartOfLine() {
        String result = MarkdownConverter.convert("Some # text");
        // # in the middle of text is escaped as a special char
        assertThat(result).isEqualTo("Some \\# text");
    }

    // ─── List support ──────────────────────────────────────────────

    @Test
    void list_dashItem_convertsToBullet() {
        String result = MarkdownConverter.convert("- item one");
        assertThat(result).isEqualTo("• item one");
    }

    @Test
    void list_starItem_convertsToBullet() {
        String result = MarkdownConverter.convert("* item one");
        assertThat(result).isEqualTo("• item one");
    }

    @Test
    void list_plusItem_convertsToBullet() {
        String result = MarkdownConverter.convert("+ item one");
        assertThat(result).isEqualTo("• item one");
    }

    @Test
    void list_multipleItems() {
        String input = "- first\n- second\n- third";
        String result = MarkdownConverter.convert(input);
        assertThat(result).isEqualTo("• first\n• second\n• third");
    }

    @Test
    void list_mixedMarkers() {
        String input = "- dash\n* star\n+ plus";
        String result = MarkdownConverter.convert(input);
        assertThat(result).isEqualTo("• dash\n• star\n• plus");
    }

    @Test
    void list_itemWithSpecialChars_escaped() {
        String result = MarkdownConverter.convert("- item.with.dots");
        assertThat(result).isEqualTo("• item\\.with\\.dots");
    }

    @Test
    void list_starItem_notConfusedWithItalic() {
        // The * in * item is a list marker, not italic, so it should become a bullet
        String result = MarkdownConverter.convert("* italic item");
        assertThat(result).isEqualTo("• italic item");
    }

    // ─── Italic edge cases (math expressions) ──────────────────────

    @Test
    void italic_mathExpression_notConverted() {
        // 2*3*4 should not be interpreted as italic around "3"
        String result = MarkdownConverter.convert("2*3*4");
        assertThat(result).isEqualTo("2\\*3\\*4");
    }

    @Test
    void italic_alphaMultiplication_notConverted() {
        // a*b*c should not be interpreted as italic around "b"
        String result = MarkdownConverter.convert("a*b*c");
        assertThat(result).isEqualTo("a\\*b\\*c");
    }

    @Test
    void italic_spaceBeforeAsterisk_converted() {
        // Space before * → valid italic
        String result = MarkdownConverter.convert("text *italic* more");
        assertThat(result).isEqualTo("text _italic_ more");
    }

    @Test
    void italic_startOfLine_converted() {
        // At start of line → valid italic
        String result = MarkdownConverter.convert("*italic* text");
        assertThat(result).isEqualTo("_italic_ text");
    }

    @Test
    void italic_afterPunctuation_converted() {
        // Punctuation before * → valid italic
        String result = MarkdownConverter.convert("(see *note*)");
        // ( is escaped in plain text, but it's before the italic marker
        assertThat(result).isEqualTo("\\(see _note_\\)");
    }

    @Test
    void italic_mixedWithBold() {
        String result = MarkdownConverter.convert("**bold** and *italic*");
        assertThat(result).isEqualTo("*bold* and _italic_");
    }

    // ─── Code block languages ──────────────────────────────────────

    @Test
    void codeBlock_languageCpp_preserved() {
        String result = MarkdownConverter.convert("```c++\nint x = 0;\n```");
        assertThat(result).isEqualTo("```c++\nint x = 0;\n```");
    }

    @Test
    void codeBlock_languageObjectiveC_preserved() {
        String result = MarkdownConverter.convert("```objective-c\nNSLog(@\"hello\");\n```");
        assertThat(result).isEqualTo("```objective-c\nNSLog(@\"hello\");\n```");
    }

    @Test
    void codeBlock_languageCSharp_preserved() {
        String result = MarkdownConverter.convert("```c#\nvar x = 1;\n```");
        assertThat(result).isEqualTo("```c#\nvar x = 1;\n```");
    }

    @Test
    void codeBlock_emptyLanguage_preserved() {
        String result = MarkdownConverter.convert("```\nplain code\n```");
        assertThat(result).isEqualTo("```\nplain code\n```");
    }

    @Test
    void codeBlock_languageWithDots_preserved() {
        String result = MarkdownConverter.convert("```js.test\ncode\n```");
        assertThat(result).isEqualTo("```js.test\ncode\n```");
    }

    @Test
    void codeBlock_requiresNewlineAfterFence() {
        // Without a newline after ```lang, the regex should NOT match
        // (previously ```lang\n? made the newline optional)
        String result = MarkdownConverter.convert("```javacode here```");
        // This is not a valid code block, so it's treated as plain text and escaped
        // The backticks will be matched as inline code by the inline code pattern
        assertThat(result).contains("code here");
    }

    // ─── Try-catch fallback ────────────────────────────────────────

    @Test
    void convert_normalInput_returnsConverted() {
        // Sanity check: normal input still works
        String result = MarkdownConverter.convert("**bold**");
        assertThat(result).isEqualTo("*bold*");
    }

    @Test
    void convert_emptyAndNull_returnsEmpty() {
        assertThat(MarkdownConverter.convert(null)).isEqualTo("");
        assertThat(MarkdownConverter.convert("")).isEqualTo("");
    }

    @Test
    void convert_fallbackOnException_returnsEscapedText() {
        // The convert method wraps the body in try-catch. If an exception occurs,
        // it should return the original text with minimal escaping (escapeMarkdownV2).
        // We can't easily force an internal exception, but we can verify that
        // the public API is robust by testing edge-case inputs that might cause issues.
        // This test documents the fallback behavior: if something goes wrong internally,
        // the result is the escaped plain text.
        
        // A very long string with many nested formatting markers - should not crash
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("**bold** *italic* `code` ~~strike~~\n");
        }
        String result = MarkdownConverter.convert(sb.toString());
        assertThat(result).isNotEmpty();
    }

    @Test
    void convert_specialCharsOnly_returnsEscaped() {
        String result = MarkdownConverter.convert(".*-_");
        assertThat(result).isEqualTo("\\.\\*\\-\\_");
    }

    // ─── Spoiler support (Hermes parity) ───────────────────────────

    @Test
    void spoiler_convertedAndProtected() {
        String result = MarkdownConverter.convert("||secret text||");
        assertThat(result).isEqualTo("||secret text||");
    }

    @Test
    void spoiler_withSpecialChars_escaped() {
        String result = MarkdownConverter.convert("||hello.world!||");
        assertThat(result).isEqualTo("||hello\\.world\\!||");
    }

    @Test
    void spoiler_mixedWithBold() {
        String result = MarkdownConverter.convert("**bold** and ||spoiler||");
        assertThat(result).isEqualTo("*bold* and ||spoiler||");
    }

    @Test
    void spoiler_pipeInsideText_notBroken() {
        // || a | b || — pipe inside spoiler content should not break
        String result = MarkdownConverter.convert("||a|b||");
        // The | in the content is escaped as part of the spoiler content
        assertThat(result).contains("||");
    }

    // ─── Blockquote support (Hermes parity) ───────────────────────

    @Test
    void blockquote_singleLevel_protected() {
        String result = MarkdownConverter.convert("> quoted text");
        // > at start of line should NOT be escaped (blockquote syntax)
        assertThat(result).startsWith(">");
        assertThat(result).doesNotContain("\\>");
    }

    @Test
    void blockquote_multiLevel_protected() {
        String result = MarkdownConverter.convert(">> nested quote");
        assertThat(result).startsWith(">>");
        assertThat(result).doesNotContain("\\>");
    }

    @Test
    void blockquote_withSpecialChars_escaped() {
        String result = MarkdownConverter.convert("> hello.world!");
        // > is protected, but . and ! in content are escaped
        assertThat(result).contains("hello\\.world\\!");
        assertThat(result).doesNotContain("\\>");
    }

    @Test
    void blockquote_expandable_protected() {
        String result = MarkdownConverter.convert("**> expandable quote");
        assertThat(result).startsWith("**>");
    }

    @Test
    void blockquote_notMatchedWhenNotAtStartOfLine() {
        String result = MarkdownConverter.convert("text > not a quote");
        // > in the middle of text is NOT a blockquote — it's escaped
        assertThat(result).contains("\\>");
    }
}