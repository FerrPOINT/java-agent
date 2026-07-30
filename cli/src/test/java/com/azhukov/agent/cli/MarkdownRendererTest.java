package com.azhukov.agent.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer(true);

    // ── Bold ──

    @Test
    void rendersBold() {
        String result = renderer.render("This is **bold** text");
        assertThat(result).contains(MarkdownRenderer.BOLD + "bold" + MarkdownRenderer.RESET);
        assertThat(result).doesNotContain("**");
    }

    @Test
    void rendersMultipleBold() {
        String result = renderer.render("**first** and **second**");
        assertThat(result).contains(MarkdownRenderer.BOLD + "first" + MarkdownRenderer.RESET);
        assertThat(result).contains(MarkdownRenderer.BOLD + "second" + MarkdownRenderer.RESET);
    }

    // ── Italic ──

    @Test
    void rendersItalic() {
        String result = renderer.render("This is *italic* text");
        assertThat(result).contains(MarkdownRenderer.ITALIC + "italic" + MarkdownRenderer.RESET);
        assertThat(result).doesNotContain("*italic*");
    }

    // ── Inline code ──

    @Test
    void rendersInlineCode() {
        String result = renderer.render("Use `System.out` for output");
        assertThat(result).contains(MarkdownRenderer.CYAN + "System.out" + MarkdownRenderer.RESET);
        assertThat(result).doesNotContain("`");
    }

    // ── Code blocks ──

    @Test
    void rendersCodeBlock() {
        String markdown = "```java\nSystem.out.println(\"hi\");\n```";
        String result = renderer.render(markdown);
        assertThat(result).contains(MarkdownRenderer.DIM_CYAN);
        assertThat(result).contains("System.out.println");
        assertThat(result).doesNotContain("```");
    }

    @Test
    void rendersCodeBlockWithLanguage() {
        String markdown = "```python\nprint('hello')\n```";
        String result = renderer.render(markdown);
        assertThat(result).contains("print('hello')");
        assertThat(result).doesNotContain("```");
        assertThat(result).doesNotContain("python");
    }

    // ── Headers ──

    @Test
    void rendersH1() {
        String result = renderer.render("# Title");
        assertThat(result).contains(MarkdownRenderer.BOLD);
        assertThat(result).contains(MarkdownRenderer.UNDERLINE);
        assertThat(result).contains("Title");
        assertThat(result).doesNotContain("#");
    }

    @Test
    void rendersH2() {
        String result = renderer.render("## Section");
        assertThat(result).contains(MarkdownRenderer.BOLD);
        assertThat(result).contains("Section");
        assertThat(result).doesNotContain("##");
    }

    @Test
    void rendersH3() {
        String result = renderer.render("### Subsection");
        assertThat(result).contains(MarkdownRenderer.BOLD);
        assertThat(result).contains(MarkdownRenderer.DIM);
        assertThat(result).contains("Subsection");
    }

    // ── Links ──

    @Test
    void rendersLink() {
        String result = renderer.render("See [docs](https://example.com) for details");
        assertThat(result).contains("docs (https://example.com)");
        assertThat(result).contains(MarkdownRenderer.DIM);
        assertThat(result).doesNotContain("[docs]");
    }

    // ── Strikethrough ──

    @Test
    void rendersStrikethrough() {
        String result = renderer.render("This is ~~old~~ text");
        assertThat(result).contains(MarkdownRenderer.DIM + "old" + MarkdownRenderer.RESET);
        assertThat(result).doesNotContain("~~");
    }

    // ── Dumb terminal (no ANSI) ──

    @Test
    void dumbTerminalStripsFormatting() {
        MarkdownRenderer dumb = new MarkdownRenderer(false);
        String result = dumb.render("**bold** and *italic* and `code`");
        assertThat(result).doesNotContain("\033[");
        assertThat(result).contains("bold");
        assertThat(result).contains("italic");
        assertThat(result).contains("code");
        assertThat(result).doesNotContain("**");
        assertThat(result).doesNotContain("*italic*");
        assertThat(result).doesNotContain("`");
    }

    @Test
    void dumbTerminalRendersCodeBlock() {
        MarkdownRenderer dumb = new MarkdownRenderer(false);
        String result = dumb.render("```\ncode here\n```");
        assertThat(result).doesNotContain("\033[");
        assertThat(result).contains("code here");
        assertThat(result).doesNotContain("```");
    }

    @Test
    void dumbTerminalRendersHeaders() {
        MarkdownRenderer dumb = new MarkdownRenderer(false);
        String result = dumb.render("# Header");
        assertThat(result).doesNotContain("\033[");
        assertThat(result).contains("Header");
        assertThat(result).doesNotContain("#");
    }

    @Test
    void dumbTerminalRendersLink() {
        MarkdownRenderer dumb = new MarkdownRenderer(false);
        String result = dumb.render("[text](url)");
        assertThat(result).doesNotContain("\033[");
        assertThat(result).isEqualTo("text (url)");
    }

    // ── Edge cases ──

    @Test
    void handlesNullInput() {
        assertThat(renderer.render(null)).isEmpty();
    }

    @Test
    void handlesEmptyInput() {
        assertThat(renderer.render("")).isEmpty();
    }

    @Test
    void handlesPlainTextInput() {
        String result = renderer.render("Just plain text");
        assertThat(result).isEqualTo("Just plain text");
    }

    @Test
    void handlesBulletPoints() {
        String result = renderer.render("- item 1\n- item 2");
        assertThat(result).contains("•");
        assertThat(result).doesNotContain("- item");
    }

    @Test
    void handlesImageSyntax() {
        String result = renderer.render("![alt text](image.png)");
        assertThat(result).contains("alt text");
        assertThat(result).doesNotContain("![");
        assertThat(result).doesNotContain("](image.png)");
    }

    @Test
    void handlesHorizontalRule() {
        String result = renderer.render("---");
        assertThat(result).contains("───");
    }

    @Test
    void preservesNonMarkdownText() {
        String result = renderer.render("Hello world, this is regular text with no formatting.");
        assertThat(result).isEqualTo("Hello world, this is regular text with no formatting.");
    }

    @Test
    void handlesMixedFormatting() {
        String result = renderer.render("# Title\n\n**Bold** and *italic* and `code`");
        assertThat(result).contains("Title");
        assertThat(result).contains(MarkdownRenderer.BOLD);
        assertThat(result).contains(MarkdownRenderer.ITALIC);
        assertThat(result).contains(MarkdownRenderer.CYAN);
    }
}