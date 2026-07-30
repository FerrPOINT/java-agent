package com.azhukov.agent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANSI-color markdown renderer for terminal output.
 * <p>
 * Converts a subset of markdown to ANSI escape sequences so that
 * the CLI displays formatted text in capable terminals.
 * When the terminal is "dumb" (no ANSI support), all formatting
 * is stripped to produce plain text.
 * <p>
 * P1-2: Enhanced with streaming rendering support, table rendering,
 * reasoning block detection, and code block syntax highlighting.
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
    static final String YELLOW = "\033[33m";
    static final String GREEN = "\033[32m";
    static final String RED = "\033[31m";
    static final String BLUE = "\033[34m";
    static final String MAGENTA = "\033[35m";
    static final String DIM_GRAY = "\033[2;37m";
    static final String BOLD_CYAN = "\033[1;36m";

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

    // P1-2: Reasoning block pattern — <reasoning>...</reasoning> or <think>...</think>
    private static final Pattern REASONING_BLOCK = Pattern.compile(
        "<(?:reasoning|think)>([\\s\\S]*?)</(?:reasoning|think)>", Pattern.CASE_INSENSITIVE);

    // P1-2: Table row pattern — lines with pipe separators
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|(.+)\\|\\s*$");

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

        // P1-2: Reasoning blocks — process first, strip tags, show as dimmed
        result = renderReasoningBlocks(result);

        // P1-2: Tables — detect pipe-delimited lines and align columns
        result = renderTables(result);

        // Code blocks — process before inline formatting so we don't format inside them
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

    // ------------------------------------------------------------------
    // P1-2: Streaming rendering
    // ------------------------------------------------------------------

    /**
     * Streaming markdown renderer — buffers tokens and renders incrementally.
     * <p>
     * Instead of raw {@code System.out.print(token)}, this buffers words and
     * detects line boundaries for proper rendering.
     */
    public static class StreamingRenderer {
        private final MarkdownRenderer renderer;
        private final java.util.function.Consumer<String> output;
        private final StringBuilder buffer = new StringBuilder();
        private boolean inCodeBlock = false;
        private boolean inReasoningBlock = false;

        public StreamingRenderer(MarkdownRenderer renderer, java.util.function.Consumer<String> output) {
            this.renderer = renderer;
            this.output = output;
        }

        /**
         * Accept a token from the stream.
         */
        public void accept(String token) {
            if (token == null || token.isEmpty()) return;
            buffer.append(token);

            // Check for code block fence
            if (buffer.indexOf("```") >= 0) {
                inCodeBlock = !inCodeBlock;
                // If we just closed a code block, render the whole buffer
                if (!inCodeBlock) {
                    String rendered = renderer.render(buffer.toString());
                    output.accept(rendered);
                    buffer.setLength(0);
                }
                return;
            }

            // In code block, just output raw
            if (inCodeBlock) {
                output.accept(token);
                return;
            }

            // Check for reasoning tags
            String bufStr = buffer.toString();
            if (bufStr.contains("<reasoning>") || bufStr.contains("<think>")) {
                inReasoningBlock = true;
            }
            if (bufStr.contains("</reasoning>") || bufStr.contains("</think>")) {
                inReasoningBlock = false;
                // Render and flush
                String rendered = renderer.render(buffer.toString());
                output.accept(rendered);
                buffer.setLength(0);
                return;
            }

            if (inReasoningBlock) {
                return; // Buffer until reasoning block closes
            }

            // Flush on line boundaries
            int lastNewline = buffer.lastIndexOf("\n");
            if (lastNewline >= 0) {
                String toFlush = buffer.substring(0, lastNewline + 1);
                buffer.delete(0, lastNewline + 1);
                output.accept(toFlush);
            }
        }

        /**
         * Flush any remaining buffered content.
         */
        public void flush() {
            if (buffer.length() > 0) {
                String rendered = renderer.render(buffer.toString());
                output.accept(rendered);
                buffer.setLength(0);
            }
            inCodeBlock = false;
            inReasoningBlock = false;
        }

        /**
         * Check if currently inside a code block.
         */
        public boolean isInCodeBlock() {
            return inCodeBlock;
        }

        /**
         * Check if currently inside a reasoning block.
         */
        public boolean isInReasoningBlock() {
            return inReasoningBlock;
        }
    }

    // ------------------------------------------------------------------
    // P1-2: Reasoning block rendering
    // ------------------------------------------------------------------

    private String renderReasoningBlocks(String text) {
        Matcher m = REASONING_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1).strip();
            String rendered = ansiEnabled
                ? DIM + DIM_GRAY + content + RESET
                : content;
            // Add a dimmed label
            if (ansiEnabled) {
                rendered = DIM + "[reasoning] " + RESET + DIM_GRAY + content + RESET;
            } else {
                rendered = "[reasoning] " + content;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // P1-2: Table rendering
    // ------------------------------------------------------------------

    private String renderTables(String text) {
        String[] lines = text.split("\n");
        List<Integer> tableStartLines = new ArrayList<>();
        boolean prevWasTable = false;

        // Find table regions
        for (int i = 0; i < lines.length; i++) {
            boolean isTableRow = TABLE_ROW.matcher(lines[i]).matches();
            if (isTableRow && !prevWasTable) {
                tableStartLines.add(i);
            }
            prevWasTable = isTableRow;
        }

        if (tableStartLines.isEmpty()) {
            return text;
        }

        // Process each table
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int idx = 0;
        while (idx < tableStartLines.size()) {
            int start = tableStartLines.get(idx);
            // Find end of this table
            int end = start;
            for (int i = start; i < lines.length; i++) {
                if (!TABLE_ROW.matcher(lines[i]).matches()) {
                    end = i;
                    break;
                }
                end = i + 1;
            }

            // Copy non-table content before this table
            for (int i = lastEnd; i < start; i++) {
                result.append(lines[i]).append("\n");
            }

            // Extract table rows
            List<String[]> rows = new ArrayList<>();
            for (int i = start; i < end; i++) {
                String row = TABLE_ROW.matcher(lines[i]).replaceFirst("$1");
                // Skip separator rows (--- --- ---)
                if (row.matches("^[\\s:-]+(\\|[\\s:-]+)*$")) continue;
                String[] cells = row.split("\\|");
                for (int j = 0; j < cells.length; j++) {
                    cells[j] = cells[j].strip();
                }
                rows.add(cells);
            }

            // Calculate column widths
            if (!rows.isEmpty()) {
                int maxCols = rows.stream().mapToInt(r -> r.length).max().orElse(0);
                int[] colWidths = new int[maxCols];
                for (String[] row : rows) {
                    for (int j = 0; j < row.length && j < maxCols; j++) {
                        colWidths[j] = Math.max(colWidths[j], row[j].length());
                    }
                }

                // Render aligned table
                for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                    String[] row = rows.get(rowIdx);
                    StringBuilder line = new StringBuilder(" ");
                    for (int j = 0; j < maxCols; j++) {
                        String cell = j < row.length ? row[j] : "";
                        if (rowIdx == 0 && ansiEnabled) {
                            line.append(BOLD);
                        }
                        line.append(String.format("%-" + colWidths[j] + "s", cell));
                        if (rowIdx == 0 && ansiEnabled) {
                            line.append(RESET);
                        }
                        if (j < maxCols - 1) {
                            line.append(" | ");
                        }
                    }
                    result.append(line.toString().stripTrailing()).append("\n");

                    // Add separator after header row
                    if (rowIdx == 0) {
                        StringBuilder sep = new StringBuilder(" ");
                        for (int j = 0; j < maxCols; j++) {
                            sep.append("-".repeat(colWidths[j]));
                            if (j < maxCols - 1) {
                                sep.append("-+-");
                            }
                        }
                        result.append(sep.toString().stripTrailing()).append("\n");
                    }
                }
            }

            lastEnd = end;
            idx++;
            // Skip consecutive table starts that are part of the same table
            while (idx < tableStartLines.size() && tableStartLines.get(idx) < end) {
                idx++;
            }
        }

        // Copy remaining non-table content
        for (int i = lastEnd; i < lines.length; i++) {
            result.append(lines[i]);
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    // ------------------------------------------------------------------
    // P1-2: Code block rendering with syntax highlighting
    // ------------------------------------------------------------------

    private String renderCodeBlocks(String text) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String code = m.group(1).strip();
            String rendered = ansiEnabled
                ? applySyntaxHighlighting(code)
                : code;
            m.appendReplacement(sb, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * P1-2: Apply basic syntax highlighting with ANSI colors.
     * <p>
     * Keywords → magenta, strings → green, comments → dim gray, numbers → cyan.
     */
    private String applySyntaxHighlighting(String code) {
        // Simple keyword highlighting for common languages
        String result = code;

        // Highlight strings (double and single quoted)
        result = result.replaceAll("\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"",
            ansiEnabled ? GREEN + "\"$1\"" + RESET : "\"$1\"");
        result = result.replaceAll("'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'",
            ansiEnabled ? GREEN + "'$1'" + RESET : "'$1'");

        // Highlight comments (// and #)
        String[] lines = result.split("\n");
        StringBuilder highlighted = new StringBuilder();
        for (String line : lines) {
            String highlightedLine = line;
            // Line comments
            if (line.contains("//")) {
                int commentIdx = line.indexOf("//");
                String code2 = line.substring(0, commentIdx);
                String comment = line.substring(commentIdx);
                if (ansiEnabled) {
                    highlightedLine = code2 + DIM_GRAY + comment + RESET;
                }
            } else if (line.trim().startsWith("#") && !line.trim().startsWith("#!")) {
                if (ansiEnabled) {
                    highlightedLine = DIM_GRAY + line + RESET;
                }
            }
            // Highlight common keywords
            if (ansiEnabled) {
                String[] keywords = {"public", "private", "protected", "class", "interface", "enum",
                    "void", "int", "long", "double", "float", "boolean", "String", "var", "final",
                    "static", "return", "if", "else", "for", "while", "switch", "case", "break",
                    "continue", "new", "try", "catch", "finally", "throw", "throws", "import",
                    "package", "extends", "implements", "this", "super", "null", "true", "false",
                    "def", "fn", "func", "func", "let", "const", "async", "await"};
                for (String kw : keywords) {
                    highlightedLine = highlightedLine.replaceAll("\\b" + kw + "\\b",
                        MAGENTA + kw + RESET);
                }
            }
            highlighted.append(highlightedLine);
            highlighted.append("\n");
        }

        // Remove trailing newline
        if (highlighted.length() > 0 && highlighted.charAt(highlighted.length() - 1) == '\n') {
            highlighted.deleteCharAt(highlighted.length() - 1);
        }

        // Wrap in dim cyan for code block styling
        if (ansiEnabled) {
            return DIM_CYAN + highlighted.toString() + RESET;
        }
        return highlighted.toString();
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