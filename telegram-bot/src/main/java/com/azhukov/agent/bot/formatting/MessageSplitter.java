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
        if (text == null || text.isEmpty()) {
            return List.of("");
        }

        if (utf16Length(text) <= TELEGRAM_MAX_LENGTH) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();

        // Split by paragraphs (double newline)
        String[] paragraphs = text.split("\n\n", -1);
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            String paraWithJoin = paragraph;
            if (current.length() > 0) {
                paraWithJoin = current + "\n\n" + paragraph;
            }

            if (utf16Length(paraWithJoin) <= TELEGRAM_MAX_LENGTH) {
                // Fits in current chunk
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(paragraph);
            } else {
                // Doesn't fit — flush current chunk first
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }

                // Now try to fit paragraph alone
                if (utf16Length(paragraph) <= TELEGRAM_MAX_LENGTH) {
                    current.append(paragraph);
                } else {
                    // Paragraph too big — split by lines
                    splitByLines(paragraph, chunks, current);
                }
            }
        }

        // Flush remaining
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    private static void splitByLines(String paragraph, List<String> chunks, StringBuilder current) {
        String[] lines = paragraph.split("\n", -1);
        for (String line : lines) {
            String lineWithJoin = line;
            if (current.length() > 0) {
                lineWithJoin = current + "\n" + line;
            }

            if (utf16Length(lineWithJoin) <= TELEGRAM_MAX_LENGTH) {
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
                if (utf16Length(line) <= TELEGRAM_MAX_LENGTH) {
                    current.append(line);
                } else {
                    splitHard(line, chunks, current);
                }
            }
        }
    }

    private static void splitHard(String text, List<String> chunks, StringBuilder current) {
        int start = 0;
        while (start < text.length()) {
            int remaining = text.length() - start;
            // Calculate how many chars fit in TELEGRAM_MAX_LENGTH UTF-16 code units
            int capacity = TELEGRAM_MAX_LENGTH - utf16Length(current.toString());
            if (capacity <= 0) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                capacity = TELEGRAM_MAX_LENGTH;
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

            if (utf16Length(current.toString()) >= TELEGRAM_MAX_LENGTH) {
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