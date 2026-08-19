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

        List<String> chunks = doSplit(text, maxLength);

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
        if (utf16Length(text) <= TELEGRAM_MAX_LENGTH) {
            // Single chunk — format and return without indicator
            return List.of(MarkdownConverter.convert(text));
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

        List<String> rawChunks = doSplit(text, rawMaxLen);
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