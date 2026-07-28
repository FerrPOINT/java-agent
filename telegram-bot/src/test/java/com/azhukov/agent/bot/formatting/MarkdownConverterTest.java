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
}