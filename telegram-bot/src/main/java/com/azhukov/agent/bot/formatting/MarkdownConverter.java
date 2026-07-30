package com.azhukov.agent.bot.formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts standard Markdown to Telegram MarkdownV2.
 *
 * <p>Telegram MarkdownV2 requires escaping of special characters in plain text
 * but NOT inside code blocks, inline code, or formatting markers. Bold, italic,
 * strikethrough, and links are converted to Telegram's syntax.
 *
 * <p>Processing order:
 * <ol>
 *   <li>Convert GFM pipe tables to Telegram-friendly format (bold header + bullets)</li>
 *   <li>Protect code blocks and inline code (no escaping inside)</li>
 *   <li>Protect links (escape link text, keep URL as-is)</li>
 *   <li>Convert bold {@code **text**} → {@code *escaped_text*}, protect markers</li>
 *   <li>Convert strikethrough {@code ~~text~~} → {@code ~escaped_text~}, protect markers</li>
 *   <li>Convert italic {@code *text*} → {@code _escaped_text_}, protect markers</li>
 *   <li>Escape remaining plain text</li>
 *   <li>Restore all protected segments</li>
 * </ol>
 */
public final class MarkdownConverter {

    // ─── Table conversion ──────────────────────────────────────────

    /**
     * Matches a GFM table delimiter row: optional outer pipes, cells containing
     * only dashes (with optional leading/trailing colons for alignment) separated
     * by '|'. Requires at least one internal '|' so lone '---' horizontal rules
     * are NOT matched.
     */
    private static final Pattern TABLE_SEPARATOR_PATTERN =
        Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*){1,}\\|?\\s*$");

    /**
     * Convert GFM pipe tables to Telegram-friendly format before the main
     * MarkdownV2 conversion. A table like:
     * <pre>
     * | Name | Value |
     * |------|-------|
     * | A    | 1     |
     * | B    | 2     |
     * </pre>
     * becomes:
     * <pre>
     * **Name | Value**
     * • A | 1
     * • B | 2
     * </pre>
     *
     * @param text the input markdown
     * @return text with tables converted to bullet-list format
     */
    static String convertTables(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder(text.length() + 64);

        int i = 0;
        while (i < lines.length) {
            // Look ahead for a potential table: need at least 3 consecutive lines where
            // line[i] is a header row (contains |), line[i+1] is a separator, line[i+2+] are data rows
            if (i + 1 < lines.length && isTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
                // Collect the full table block
                List<String> tableBlock = new ArrayList<>();
                tableBlock.add(lines[i]);     // header
                tableBlock.add(lines[i + 1]);  // separator
                int j = i + 2;
                while (j < lines.length && isTableRow(lines[j])) {
                    tableBlock.add(lines[j]);
                    j++;
                }

                // Render the table
                result.append(renderTableBlock(tableBlock));

                // If the table was followed by more content, add a newline separator
                if (j < lines.length) {
                    result.append("\n");
                }
                i = j;
            } else {
                result.append(lines[i]);
                if (i < lines.length - 1) {
                    result.append("\n");
                }
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Returns true if a line could plausibly be a table row (non-empty, contains |).
     */
    private static boolean isTableRow(String line) {
        String stripped = line.strip();
        return !stripped.isEmpty() && stripped.contains("|");
    }

    /**
     * Returns true if a line is a GFM table separator row (|---|---|).
     */
    private static boolean isTableSeparator(String line) {
        return TABLE_SEPARATOR_PATTERN.matcher(line).matches();
    }

    /**
     * Split a markdown table row into stripped cell values.
     * Strips leading/trailing pipes and splits on |.
     */
    private static List<String> splitTableRow(String line) {
        String stripped = line.strip();
        if (stripped.startsWith("|")) stripped = stripped.substring(1);
        if (stripped.endsWith("|")) stripped = stripped.substring(0, stripped.length() - 1);
        String[] parts = stripped.split("\\|");
        List<String> cells = new ArrayList<>(parts.length);
        for (String p : parts) {
            cells.add(p.strip());
        }
        return cells;
    }

    /**
     * Render a GFM table block as Telegram-friendly text.
     * Header row → bold text, data rows → bullet points.
     */
    private static String renderTableBlock(List<String> tableBlock) {
        if (tableBlock.size() < 3) {
            return String.join("\n", tableBlock);
        }

        List<String> headers = splitTableRow(tableBlock.get(0));
        if (headers.size() < 2) {
            return String.join("\n", tableBlock);
        }

        StringBuilder sb = new StringBuilder();

        // Header row → bold: **Header1 | Header2 | ...**
        sb.append("**").append(String.join(" | ", headers)).append("**");

        // Data rows → bullet points: • val1 | val2 | ...
        for (int k = 2; k < tableBlock.size(); k++) {
            List<String> cells = splitTableRow(tableBlock.get(k));
            sb.append("\n• ").append(String.join(" | ", cells));
        }

        return sb.toString();
    }

    private MarkdownConverter() {
    }

    /** Characters that must be escaped with backslash in MarkdownV2 plain text. */
    private static final String SPECIAL_CHARS = "_*[]()~`>#+-=|{}.!";

    // Fenced code block: ```lang\n...```
    private static final Pattern CODE_BLOCK_PATTERN =
        Pattern.compile("```(\\w*)\\n?(.*?)```", Pattern.DOTALL);

    // Inline code: `code`
    private static final Pattern INLINE_CODE_PATTERN =
        Pattern.compile("`([^`]+)`");

    // Links: [text](url)
    private static final Pattern LINK_PATTERN =
        Pattern.compile("\\[([^\\]]*)\\]\\(([^\\)]*)\\)");

    // Bold: **text**
    private static final Pattern BOLD_PATTERN =
        Pattern.compile("\\*\\*(.+?)\\*\\*");

    // Strikethrough: ~~text~~
    private static final Pattern STRIKE_PATTERN =
        Pattern.compile("~~(.+?)~~");

    // Italic: *text* (not preceded/followed by another *)
    private static final Pattern ITALIC_PATTERN =
        Pattern.compile("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)");

    /** Placeholder prefix for protected segments (code blocks, inline code, links, formatting). */
    private static final String PROTECT_PREFIX = "\u0000P";
    private static final String PROTECT_SUFFIX = "\u0000";

    /**
     * Convert standard Markdown to Telegram MarkdownV2.
     *
     * @param markdown standard Markdown text
     * @return Telegram MarkdownV2 text
     */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        // 0. Convert GFM pipe tables to Telegram-friendly format (bold header + bullets)
        markdown = convertTables(markdown);

        List<String> protectedSegments = new ArrayList<>();

        // 1. Extract and protect code blocks (```...```)
        String text = protectCodeBlocks(markdown, protectedSegments);

        // 2. Extract and protect inline code (`code`)
        text = protectInlineCode(text, protectedSegments);

        // 3. Extract and protect links [text](url)
        text = protectLinks(text, protectedSegments);

        // 4. Convert bold **text** → *escaped(text)*, protect result
        text = convertBold(text, protectedSegments);

        // 5. Convert strikethrough ~~text~~ → ~escaped(text)~, protect result
        text = convertStrikethrough(text, protectedSegments);

        // 6. Convert italic *text* → _escaped(text)_, protect result
        text = convertItalic(text, protectedSegments);

        // 7. Escape special characters in remaining plain text
        text = escapePlain(text);

        // 8. Restore all protected segments
        text = restoreProtected(text, protectedSegments);

        return text;
    }

    private static String protectCodeBlocks(String text, List<String> protectedSegments) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String lang = matcher.group(1);
            String code = matcher.group(2);
            // Preserve trailing newline if present
            String replacement;
            if (lang != null && !lang.isEmpty()) {
                replacement = "```" + lang + "\n" + code + "```";
            } else {
                replacement = "```\n" + code + "```";
            }
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectInlineCode(String text, List<String> protectedSegments) {
        Matcher matcher = INLINE_CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1);
            String replacement = "`" + code + "`";
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String protectLinks(String text, List<String> protectedSegments) {
        Matcher matcher = LINK_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String linkText = matcher.group(1);
            String url = matcher.group(2);
            // Escape special chars in link text but NOT in URL
            String replacement = "[" + escapePlain(linkText) + "](" + url + ")";
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String convertBold(String text, List<String> protectedSegments) {
        Matcher matcher = BOLD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String content = matcher.group(1);
            String replacement = "*" + escapePlain(content) + "*";
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String convertStrikethrough(String text, List<String> protectedSegments) {
        Matcher matcher = STRIKE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String content = matcher.group(1);
            String replacement = "~" + escapePlain(content) + "~";
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String convertItalic(String text, List<String> protectedSegments) {
        Matcher matcher = ITALIC_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String content = matcher.group(1);
            String replacement = "_" + escapePlain(content) + "_";
            String placeholder = makePlaceholder(protectedSegments.size());
            protectedSegments.add(replacement);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String restoreProtected(String text, List<String> protectedSegments) {
        // Restore in reverse order to handle nested placeholders correctly
        for (int i = protectedSegments.size() - 1; i >= 0; i--) {
            text = text.replace(makePlaceholder(i), protectedSegments.get(i));
        }
        return text;
    }

    private static String makePlaceholder(int index) {
        return PROTECT_PREFIX + index + PROTECT_SUFFIX;
    }

    /**
     * Escape special MarkdownV2 characters in plain text.
     *
     * @param text the plain text to escape
     * @return escaped text
     */
    private static String escapePlain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Don't escape > at start of line (blockquote syntax in Telegram MarkdownV2)
            if (c == '>' && (i == 0 || text.charAt(i - 1) == '\n')) {
                sb.append(c);
            } else if (SPECIAL_CHARS.indexOf(c) >= 0) {
                sb.append('\\');
                sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}