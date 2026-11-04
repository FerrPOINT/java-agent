package com.azhukov.agent.bot.formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks suitable for Telegram messages (max 4096 UTF-16 code units).
 * Splits on paragraph boundaries (double newline) first, then on line boundaries,
 * then hard-splits if a single line exceeds the limit.
 */
public final class MessageSplitter {

    /** Telegram message limit in UTF-16 code units. */
    public static final int TELEGRAM_MAX_LENGTH = 4096;

    private MessageSplitter() {
    }

    /**
     * Split text into chunks, each no longer than {@value #TELEGRAM_MAX_LENGTH} UTF-16 code units.
     *
     * @param text the text to split
     * @return list of chunks
     */
    public static List<String> split(String text) {
        return split(text, TELEGRAM_MAX_LENGTH);
    }

    /**
     * Split text into chunks, each no longer than maxLength UTF-16 code units.
     *
     * @param text      the text to split
     * @param maxLength max UTF-16 code units per chunk
     * @return list of chunks
     */
    public static List<String> split(String text, int maxLength) {
        if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
        if (text == null || text.isEmpty()) {
            return List.of("");
        }

        if (utf16Length(text) <= maxLength) {
            return List.of(text);
        }

        // Reserve boundary headroom when fences are present. Balancing can add
        // a closing "\n```" and a language-tagged reopening to neighbouring
        // chunks; without this reserve a hard split at 4096 overflows Telegram
        // after the otherwise-correct fence repair.
        // Reserve the complete index before splitting; truncating after adding it
        // silently loses source characters at every full-size boundary.
        int prefixReserve = 2 * Integer.toString(text.length()).length() + 4;
        // Tiny custom limits cannot fit an index and a Unicode code point.
        if (maxLength < prefixReserve + 2) {
            if (maxLength < 2 && text.codePoints().anyMatch(cp -> Character.charCount(cp) == 2)) {
                throw new IllegalArgumentException("maxLength cannot fit a Unicode code point");
            }
            return splitVerbatim(text, maxLength);
        }
        int longestTag = text.lines().map(String::strip).filter(line -> line.startsWith("```"))
            .map(line -> line.substring(3).strip())
            .map(tag -> tag.isEmpty() ? "" : tag.split("\\s+", 2)[0])
            .mapToInt(String::length).max().orElse(0);
        int fenceReserve = text.contains("```") ? longestTag + 8 : 0;
        if (maxLength - prefixReserve - fenceReserve < 2) {
            // A fence label may be longer than an entire message. Preserve the
            // source rather than inserting balancing markup and truncating it.
            return splitVerbatim(text, maxLength);
        }
        int splitLimit = maxLength - prefixReserve - fenceReserve;
        List<String> chunks = balanceFencesAcrossChunks(splitPreservingBoundaries(text, splitLimit), maxLength);

        // Add "(1/N)" continuation indicator when splitting into multiple chunks.
        // The indicator is NOT escaped here — callers using MarkdownV2 should use
        // splitAndFormat() or escape the prefix themselves.
        if (chunks.size() > 1) {
            int total = chunks.size();
            List<String> indexed = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                String prefix = "(" + (i + 1) + "/" + total + ") ";
                int prefixLen = prefix.length();
                String chunk = chunks.get(i);
                // Shrink chunk so that prefix + chunk fits within maxLength.
                // BUG FIX (audit H15): back off one char when the cut point would
                // split a surrogate pair — otherwise the chunk ends with a lone
                // high surrogate and the emoji renders broken in Telegram.
                if (chunk.length() > maxLength - prefixLen) {
                    int cut = maxLength - prefixLen;
                    if (cut > 0 && Character.isHighSurrogate(chunk.charAt(cut - 1))) {
                        cut--;
                    }
                    chunk = chunk.substring(0, cut);
                }
                indexed.add(prefix + chunk);
            }
            return indexed;
        }

        return chunks;
    }

    private static String escapeLiteralMarkdown(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("_*[]()~`>#+-=|{}.!\\".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static List<String> splitVerbatim(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            if (end == start) throw new IllegalArgumentException("maxLength cannot fit a Unicode code point");
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /**
     * Split text and format each chunk for the given parse mode.
     *
     * <p>For MarkdownV2, this splits the raw (unformatted) text first, then converts
     * each chunk via {@link MarkdownConverter#convert(String)}, then prepends the
     * {@code (1/N)} continuation indicator — escaped for MarkdownV2 so that
     * special characters like {@code (}, {@code )}, {@code /} are properly escaped.
     *
     * <p>For non-MarkdownV2 parse modes (HTML, null), this falls back to
     * {@link #split(String, int)} with the standard unescaped indicator.
     *
     * @param text      the raw (unformatted) text to split and format
     * @param parseMode the Telegram parse mode ("MarkdownV2", "HTML", or null)
     * @return list of formatted chunks with continuation indicators
     */
    public static List<String> splitAndFormat(String text, String parseMode) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }

        boolean isMarkdownV2 = "MarkdownV2".equalsIgnoreCase(parseMode);
        if (!isMarkdownV2) {
            // For HTML or plain text, use the standard split (indicator is not escaped)
            return split(text);
        }

        // For MarkdownV2: split raw text first, then format each chunk, then add escaped indicator.
        // MarkdownV2 escaping can double each char (every special char gets a '\' prefix),
        // so we split the raw text at maxLength / maxExpansionFactor to leave room for escaping.
        String converted = MarkdownConverter.convert(text);
        if (utf16Length(converted) <= TELEGRAM_MAX_LENGTH) {
            // Escaping may expand a short raw message beyond the transport limit.
            return List.of(converted);
        }

        // Worst case: every character is a MarkdownV2 special char and gets escaped (doubled).
        // Also account for the escaped continuation indicator prefix length.
        final int maxExpansionFactor = 2;
        // Reserve space for the longest possible indicator prefix "(N/M) " —
        // after escaping, each of its chars could double, but in practice the indicator
        // is short (e.g. "(10/10) " = 8 chars, escaped ≈ 12 chars). We use a safe margin.
        int indicatorReserve = 24; // generous upper bound for escaped "(NN/NN) "
        int rawMaxLen = (TELEGRAM_MAX_LENGTH - indicatorReserve) / maxExpansionFactor;
        if (rawMaxLen <= 0) {
            rawMaxLen = 1; // ensure at least 1 char per chunk
        }

        int longestTag = text.lines().map(String::strip).filter(line -> line.startsWith("```"))
            .map(line -> line.substring(3).strip())
            .map(tag -> tag.isEmpty() ? "" : tag.split("\\s+", 2)[0])
            .mapToInt(String::length).max().orElse(0);
        int fenceReserve = text.contains("```") ? longestTag + 8 : 0;
        if (rawMaxLen - fenceReserve < 64) {
            // Treat pathological fence labels as literal text. This keeps every
            // source character without manufacturing oversized code fences.
            List<String> literalChunks = splitPreservingBoundaries(text, rawMaxLen);
            List<String> literal = new ArrayList<>(literalChunks.size());
            for (int i = 0; i < literalChunks.size(); i++) {
                String indicator = "(" + (i + 1) + "/" + literalChunks.size() + ") ";
                literal.add(MarkdownConverter.escapeMarkdownV2(indicator)
                    + escapeLiteralMarkdown(literalChunks.get(i)));
            }
            return literal;
        }
        List<String> rawChunks = balanceFencesAcrossChunks(
            splitPreservingBoundaries(text, rawMaxLen - fenceReserve), rawMaxLen);
        int total = rawChunks.size();
        List<String> result = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            String formatted = MarkdownConverter.convert(rawChunks.get(i));
            if (total > 1) {
                // Build the indicator and escape it for MarkdownV2
                String indicator = "(" + (i + 1) + "/" + total + ") ";
                String escapedIndicator = MarkdownConverter.escapeMarkdownV2(indicator);
                // Truncate formatted text if it still exceeds the limit with the prefix
                int remaining = TELEGRAM_MAX_LENGTH - escapedIndicator.length();
                if (formatted.length() > remaining) {
                    // BUG FIX (audit H15): avoid splitting a surrogate pair at the
                    // cut point — a lone high surrogate breaks the emoji.
                    int cut = remaining;
                    if (cut > 0 && Character.isHighSurrogate(formatted.charAt(cut - 1))) {
                        cut--;
                    }
                    formatted = formatted.substring(0, cut);
                }
                result.add(escapedIndicator + formatted);
            } else {
                result.add(formatted);
            }
        }

        return result;
    }

    /**
     * Hermes gateway.platforms.helpers.balance_fences_across_chunks: each
     * independently-delivered chunk must close an open triple-backtick fence;
     * the following chunk reopens it with the original language tag.
     */
    private static List<String> balanceFencesAcrossChunks(List<String> chunks, int maxLength) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        List<String> balanced = new ArrayList<>(chunks.size());
        String carryLang = null;
        for (String chunk : chunks) {
            String prefix = carryLang != null ? "```" + carryLang + "\n" : "";
            boolean inCode = carryLang != null;
            String language = carryLang != null ? carryLang : "";
            for (String line : chunk.split("\n", -1)) {
                String stripped = line.strip();
                if (!stripped.startsWith("```")) {
                    continue;
                }
                if (inCode) {
                    inCode = false;
                    language = "";
                } else {
                    inCode = true;
                    String tag = stripped.substring(3).strip();
                    language = tag.isEmpty() ? "" : tag.split("\\s+", 2)[0];
                }
            }
            String body = prefix + chunk;
            if (inCode) {
                body += "\n```";
                carryLang = language;
            } else {
                carryLang = null;
            }
            // The pre-split headroom is intentionally conservative. If a
            // pathological language tag exceeds it, keep valid fences over a
            // malformed split; a later delivery path can still split normally.
            balanced.add(body);
        }
        return balanced;
    }

    private static List<String> splitPreservingBoundaries(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length()) {
                if (Character.isHighSurrogate(text.charAt(end - 1))
                        && Character.isLowSurrogate(text.charAt(end))) end--;
                int paragraph = text.lastIndexOf("\n\n", end - 2);
                int newline = text.lastIndexOf('\n', end - 1);
                int boundary = paragraph >= start ? paragraph + 2 : newline >= start ? newline + 1 : end;
                end = boundary > start ? boundary : end;
            }
            if (end == start) throw new IllegalArgumentException("maxLength cannot fit a Unicode code point");
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    /**
     * Calculate the UTF-16 code unit length of a string.
     *
     * @param s the string
     * @return number of UTF-16 code units
     */
    private static int utf16Length(String s) {
        return s.length(); // Java String.length() returns UTF-16 code unit count
    }
}