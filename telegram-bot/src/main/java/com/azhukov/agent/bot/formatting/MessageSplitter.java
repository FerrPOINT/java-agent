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
        int splitLimit = text.contains("```") ? Math.max(1, maxLength - 16) : maxLength;
        List<String> chunks = balanceFencesAcrossChunks(doSplit(text, splitLimit), maxLength);

        // Add "(1/N)" continuation indicator when splitting into multiple chunks.
        // The indicator is NOT escaped here — callers using MarkdownV2 should use
        // splitMarkdown() or escape the prefix themselves.
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

    private static List<String> doSplit(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();

        // Split by paragraphs (double newline), but not inside code blocks
        StringBuilder current = new StringBuilder();
        boolean insideCodeBlock = false;

        // Find all paragraph boundaries, tracking code block state
        int start = 0;
        int pos;
        while ((pos = text.indexOf("\n\n", start)) >= 0) {
            String segment = text.substring(start, pos);
            // Check if this segment toggles a code block
            boolean segmentHasFence = containsCodeFence(segment);
            if (insideCodeBlock) {
                // Inside code block — don't split here, just accumulate
                current.append(segment).append("\n\n");
                if (segmentHasFence) {
                    insideCodeBlock = false;
                }
            } else {
                // Outside code block — safe to split
                if (segmentHasFence) {
                    insideCodeBlock = true;
                }
                // Try to fit segment + current
                String paraWithJoin = current.length() > 0 ? current + "\n\n" + segment : segment;
                if (utf16Length(paraWithJoin) <= maxLength) {
                    if (current.length() > 0) current.append("\n\n");
                    current.append(segment);
                } else {
                    if (current.length() > 0) {
                        chunks.add(current.toString());
                        current.setLength(0);
                    }
                    if (utf16Length(segment) <= maxLength) {
                        current.append(segment);
                    } else {
                        splitByLines(segment, chunks, current, maxLength);
                    }
                }
            }
            start = pos + 2;
        }

        // Handle remaining text
        if (start < text.length()) {
            String remaining = text.substring(start);
            if (current.length() > 0) {
                String combined = current + "\n\n" + remaining;
                if (utf16Length(combined) <= maxLength) {
                    current.append("\n\n").append(remaining);
                } else {
                    chunks.add(current.toString());
                    current.setLength(0);
                    if (utf16Length(remaining) <= maxLength) {
                        current.append(remaining);
                    } else {
                        splitByLines(remaining, chunks, current, maxLength);
                    }
                }
            } else {
                if (utf16Length(remaining) <= maxLength) {
                    current.append(remaining);
                } else {
                    splitByLines(remaining, chunks, current, maxLength);
                }
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    /** Check if a text segment contains a markdown code fence (``` or ~~~). */
    private static boolean containsCodeFence(String segment) {
        return segment.contains("```") || segment.contains("~~~");
    }

    private static void splitByLines(String paragraph, List<String> chunks, StringBuilder current, int maxLength) {
        String[] lines = paragraph.split("\n", -1);
        for (String line : lines) {
            String lineWithJoin = line;
            if (current.length() > 0) {
                lineWithJoin = current + "\n" + line;
            }

            if (utf16Length(lineWithJoin) <= maxLength) {
                if (current.length() > 0) {
                    current.append("\n");
                }
                current.append(line);
            } else {
                // Flush current
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }

                // Single line too big — hard split
                if (utf16Length(line) <= maxLength) {
                    current.append(line);
                } else {
                    splitHard(line, chunks, current, maxLength);
                }
            }
        }
    }

    private static void splitHard(String text, List<String> chunks, StringBuilder current, int maxLength) {
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            // Calculate how many chars fit in maxLength UTF-16 code units
            int capacity = maxLength - utf16Length(current.toString());
            if (capacity <= 0) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                capacity = maxLength;
            }

            int end = start;
            int codeUnits = 0;
            while (end < text.length() && codeUnits < capacity) {
                char c = text.charAt(end);
                if (Character.isSurrogate(c)) {
                    if (end + 1 < text.length() && Character.isSurrogatePair(c, text.charAt(end + 1))) {
                        if (codeUnits + 2 > capacity) break;
                        codeUnits += 2;
                        end += 2;
                    } else {
                        codeUnits += 1;
                        end += 1;
                    }
                } else {
                    codeUnits += 1;
                    end += 1;
                }
            }

            if (end == start) {
                // Can't fit anything — flush and retry
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(text, start, end);
            start = end;

            if (utf16Length(current.toString()) >= maxLength) {
                chunks.add(current.toString());
                current.setLength(0);
            }
        }
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