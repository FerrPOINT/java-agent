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

        // Add "(1/N)" continuation indicator when splitting into multiple chunks
        if (chunks.size() > 1) {
            int total = chunks.size();
            List<String> indexed = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                indexed.add("(" + (i + 1) + "/" + total + ") " + chunks.get(i));
            }
            return indexed;
        }

        return chunks;
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