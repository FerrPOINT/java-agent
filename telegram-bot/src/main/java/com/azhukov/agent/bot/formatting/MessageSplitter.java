package com.azhukov.agent.bot.formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks suitable for Telegram messages (max 4096 UTF-16 code units).
 * Splits on paragraph boundaries (double newline) first, then on line boundaries,
 * then hard-splits if a single line exceeds the limit.
 */
public final class MessageSplitter {

    /** Split literal Telegram text without dropping whitespace or source characters. */
    public static List<String> splitPlainText(String text) {
        if (text == null) {
            return List.of("");
        }
        if (text.length() <= TELEGRAM_MAX_LENGTH) {
            return List.of(text);
        }
        // A label with two ten-digit part numbers takes at most 24 UTF-16 units.
        return splitNumberedSource(text, TELEGRAM_MAX_LENGTH - 24);
    }

    /** Split original HTML text before escaping entities. Keep every source character. */
    public static List<String> splitHtmlSource(String text) {
        int limit = TELEGRAM_MAX_LENGTH - 24;
        var parts = new ArrayList<String>();
        int start = 0;
        int length = 0;
        for (int i = 0; i < text.length();) {
            int character = text.codePointAt(i);
            int width = switch (character) {
                case '&' -> 5;
                case '<', '>' -> 4;
                default -> Character.charCount(character);
            };
            if (length + width > limit) {
                parts.add(text.substring(start, i));
                start = i;
                length = 0;
            }
            length += width;
            i += Character.charCount(character);
        }
        if (start < text.length()) parts.add(text.substring(start));
        return numberParts(parts);
    }

    private static List<String> splitNumberedSource(String text, int limit) {
        return numberParts(splitSource(text, limit));
    }

    private static List<String> numberParts(List<String> parts) {
        if (parts.size() <= 1) return parts;
        for (int i = 0; i < parts.size(); i++) {
            parts.set(i, partLabel(i, parts.size()) + parts.get(i));
        }
        return parts;
    }

    private static List<String> splitSource(String text, int limit) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + limit, text.length());
            end = safeEnd(text, end);
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    private static int safeEnd(String text, int end) {
        return end < text.length() && Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))
            ? end - 1 : end;
    }

    private static String partLabel(int index, int total) {
        return "(" + (index + 1) + "/" + total + ") ";
    }

    public record TextPart(String original, String formatted) {}

    /** Prepare both representations without discarding characters at a delivery boundary. */
    public static List<TextPart> splitMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return List.of(new TextPart("", ""));
        }
        String formatted = MarkdownConverter.convert(text);
        if (formatted.length() <= TELEGRAM_MAX_LENGTH && text.length() <= TELEGRAM_MAX_LENGTH) {
            return List.of(new TextPart(text, formatted));
        }

        // Leave headroom for escaping, the part label, and closing a code fence.
        int limit = (TELEGRAM_MAX_LENGTH - 32) / 2;
        var fences = MarkdownConverter.CODE_BLOCK_PATTERN.matcher(text).results().toList();
        var parts = new ArrayList<TextPart>();
        int fenceIndex = 0;
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + limit, text.length());
            while (fenceIndex < fences.size() && fences.get(fenceIndex).end() <= start) {
                fenceIndex++;
            }
            var firstFence = fenceIndex < fences.size() ? fences.get(fenceIndex) : null;
            String reopen = firstFence != null && firstFence.start(2) <= start && start <= firstFence.end(2)
                ? text.substring(firstFence.start(), firstFence.start(2)) : "";
            while (fenceIndex < fences.size() && fences.get(fenceIndex).end() <= end) {
                fenceIndex++;
            }
            String close = "";
            if (fenceIndex < fences.size()) {
                var fence = fences.get(fenceIndex);
                if (fence.start() < end) {
                    if (fence.start() > start) {
                        end = fence.start();
                    } else if (end >= fence.end(2)) {
                        end = fence.end();
                    } else if (end >= fence.start(2)) {
                        close = "```";
                    }
                }
            }
            end = safeEnd(text, end);
            String fragment = text.substring(start, end);
            parts.add(new TextPart(fragment, MarkdownConverter.convert(reopen + fragment + close)));
            start = end;
        }
        if (parts.size() == 1) {
            return parts;
        }
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            String label = partLabel(i, parts.size());
            parts.set(i, new TextPart(label + part.original(),
                MarkdownConverter.escapeMarkdownV2(label) + part.formatted()));
        }
        return parts;
    }

    public static List<TextPart> prepare(String source, String parseMode) {
        if (source == null || source.isBlank()) return List.of();
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) return splitMarkdown(source);
        String formatted = format(source, parseMode);
        if (fits(source, formatted)) return List.of(new TextPart(source, formatted));
        if ("HTML".equalsIgnoreCase(parseMode)) {
            return splitHtmlSource(source).stream()
                .map(part -> new TextPart(part, format(part, parseMode))).toList();
        }
        return splitPlainText(source).stream().map(part -> new TextPart(part, part)).toList();
    }

    public static String format(String text, String parseMode) {
        if (text == null || text.isEmpty()) return "";
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) return MarkdownConverter.convert(text);
        if ("HTML".equalsIgnoreCase(parseMode)) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        return text;
    }

    public static boolean fits(String source, String formatted) {
        return Math.max(source.length(), formatted.length()) <= TELEGRAM_MAX_LENGTH;
    }
}
