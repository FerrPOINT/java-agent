package com.azhukov.agent.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple ANSI-color markdown renderer for terminal output.
 * <p>
 * Converts a subset of markdown to ANSI escape sequences so that
 * the CLI displays formatted text in capable terminals.
 * When the terminal is "dumb" (no ANSI support), all formatting
 * is stripped to produce plain text.
 */
public final class MarkdownRenderer {

    // ANSI codes
    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String ITALIC = "\033[3m";
    static final String UNDERLINE = "\033[4m";
    static final String CYAN = "\033[36m";
    static final String DIM_CYAN = "\033[2;36m";

    private static final Pattern CODE_BLOCK = Pattern.compile(
        "```[^\\n]*\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADER = Pattern.compile("^(#{1,3})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(.+?)~~");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*]\\s+", Pattern.MULTILINE);
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^---+$", Pattern.MULTILINE);

    private final boolean ansiEnabled;

    public MarkdownRenderer() {
        this(true);
    }

    public MarkdownRenderer(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    /**
     * Render markdown text to ANSI-formatted terminal output.
     *
     * @param markdown the raw markdown text
     * @return formatted string with ANSI codes (or plain text if dumb terminal)
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String result = markdown;

        // Code blocks — process first so we don't format inside them
        result = renderCodeBlocks(result);

        // Headers
        result = renderHeaders(result);

        // Bold
        result = renderBold(result);

        // Italic
        result = renderItalic(result);

        // Inline code
        result = renderInlineCode(result);

        // Links
        result = renderLinks(result);

        // Strikethrough
        result = renderStrikethrough(result);

        // Horizontal rules
        result = renderHorizontalRules(result);

        // Bullet points (strip markers, keep indentation)
        result = renderBullets(result);

        // Strip any remaining unrendered markdown syntax
        result = stripRemaining(result);

        return result;
    }

    private String renderCodeBlocks(String text) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).strip();
            String rendered = ansiEnabled
                ? DIM_CYAN + code + RESET
                : code;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderBold(String text) {
        Matcher m = BOLD_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            String rendered = ansiEnabled
                ? BOLD + content + RESET
                : content;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderItalic(String text) {
        Matcher m = ITALIC_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            String rendered = ansiEnabled
                ? ITALIC + content + RESET
                : content;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderInlineCode(String text) {
        Matcher m = INLINE_CODE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            String rendered = ansiEnabled
                ? CYAN + content + RESET
                : content;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderHeaders(String text) {
        Matcher m = HEADER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hashes = m.group(1);
            String content = m.group(2);
            int level = hashes.length();
            String rendered;
            if (ansiEnabled) {
                if (level == 1) {
                    rendered = BOLD + UNDERLINE + content + RESET;
                } else if (level == 2) {
                    rendered = BOLD + content + RESET;
                } else {
                    rendered = BOLD + DIM + content + RESET;
                }
            } else {
                rendered = content;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderLinks(String text) {
        Matcher m = LINK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String linkText = m.group(1);
            String url = m.group(2);
            String rendered = ansiEnabled
                ? DIM + linkText + " (" + url + ")" + RESET
                : linkText + " (" + url + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderStrikethrough(String text) {
        Matcher m = STRIKETHROUGH.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            String rendered = ansiEnabled
                ? DIM + content + RESET
                : content;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderHorizontalRules(String text) {
        Matcher m = HORIZONTAL_RULE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rendered = ansiEnabled
                ? DIM + "───────────────────────────────────────────────" + RESET
                : "───────────────────────────────────────────────";
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderBullets(String text) {
        Matcher m = BULLET.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String indent = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(indent + "  • "));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String stripRemaining(String text) {
        // Strip image syntax ![alt](url) → alt
        String result = text.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1");
        return result;
    }
}