package com.azhukov.agent.bot.streaming;

import java.util.regex.Pattern;

/**
 * B6: Think-block filtering — extracted from {@link StreamEditor}.
 *
 * <p>Contains the stateful {@link ThinkScrubber} inner class and the
 * stateless {@link #stripThinkTagsRegex(String)} regex stripper.
 *
 * <p>{@code Ӥ tags (and variants like {@code <thinking>},
 * {@code <reasoning>}) are stripped from streamed output before sending
 * to Telegram. The stateful scrubber handles split chunks where the opening
 * or closing tag spans multiple stream deltas.
 *
 * <p>Also supports {@code <reasoning_scratchpad>} tags.
 *
 * <p>Partial closing tags are stored in a field and prepended to the
 * next chunk to handle split closing tags across stream deltas.
 */
public final class ThinkTagFilter {

    private ThinkTagFilter() { }

    // ─── Static regex patterns ───────────────────────────────────

    /**
     * B6: Regex-based think-tag stripping for the final message.
     * Catches any remaining tags that the stateful scrubber might have missed.
     */
    private static final Pattern THINK_BLOCK_PATTERN =
        Pattern.compile("<(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)\\b[^>]*>.*?</(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK_ORPHAN_OPEN_PATTERN =
        Pattern.compile("(?:^|\\n)[ \\t]*<(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)\\b[^>]*>.*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK_STRAY_TAG_PATTERN =
        Pattern.compile("</?(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strip think tags from text using regex.
     * Stateless — safe to call on accumulated text.
     *
     * @param content the raw text
     * @return the text with think-block content removed
     */
    public static String stripThinkTagsRegex(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // 1. Remove closed tag pairs
        String result = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        // 2. Remove unterminated open tags (tag to end of string)
        result = THINK_ORPHAN_OPEN_PATTERN.matcher(result).replaceAll("");
        // 3. Remove stray orphan tags
        result = THINK_STRAY_TAG_PATTERN.matcher(result).replaceAll("");
        return result;
    }

    // ─── Stateful ThinkScrubber ──────────────────────────────────

    /**
     * Stateful scrubber for {@code Ӥ blocks.
     * Handles split chunks where the opening or closing tag spans
     * multiple stream deltas.
     *
     * <p>Also supports {@code <reasoning_scratchpad>} tags.
     *
     * <p>Partial closing tags are stored in a field and prepended to the
     * next chunk to handle split closing tags across stream deltas.
     */
    public static class ThinkScrubber {
        private boolean insideThinkBlock = false;
        private StringBuilder pendingTag = new StringBuilder();
        private String pendingClosingTag = null; // Partial closing tag stored across chunks
        private String accumulatedBefore = ""; // Text accumulated outside think blocks (for boundary check)

        // Exact tag lists — case-sensitive, matching Hermes behavior.
        // Using \u003C and \u003E for angle brackets to avoid encoding issues.
        // Hermes uses: <REASONING_SCRATCHPAD>, Ӥ, <reasoning>, <THINKING>, <thinking>, <thought>
        // Also includes <antml:thinking> which some models emit.
        // Lowercase <reasoning_scratchpad> included for compatibility with models that emit it.
        private static final String[] OPENING_TAGS = {
            "\u003Cthink\u003E", "\u003Cthinking\u003E", "\u003Creasoning\u003E", "\u003Cthought\u003E",
            "\u003CTHINKING\u003E", "\u003CREASONING_SCRATCHPAD\u003E", "\u003Creasoning_scratchpad\u003E", "\u003Cantml:thinking\u003E"
        };
        private static final String[] CLOSING_TAGS = {
            "\u003C/think\u003E", "\u003C/thinking\u003E", "\u003C/reasoning\u003E", "\u003C/thought\u003E",
            "\u003C/THINKING\u003E", "\u003C/REASONING_SCRATCHPAD\u003E", "\u003C/reasoning_scratchpad\u003E", "\u003C/antml:thinking\u003E"
        };
        // Prefixes for partial tag detection (case-sensitive)
        private static final String[] CLOSING_TAG_PREFIXES = {
            "\u003C/think", "\u003C/thinking", "\u003C/reasoning", "\u003C/thought",
            "\u003C/THINKING", "\u003C/REASONING_SCRATCHPAD", "\u003C/reasoning_scratchpad", "\u003C/antml:thinking"
        };
        private static final String[] OPENING_TAG_PREFIXES = {
            "\u003Cthink", "\u003Cthinking", "\u003Creasoning", "\u003Cthought",
            "\u003CTHINKING", "\u003CREASONING_SCRATCHPAD", "\u003Creasoning_scratchpad", "\u003Cantml:thinking"
        };

        /**
         * Process a text chunk, removing any think-block content.
         * Stateful: if a {@code Ӥ tag opens but no closing tag is seen,
         * all subsequent content is suppressed until the closing tag arrives.
         *
         * <p>Matches Hermes behavior:
         * <ul>
         *   <li>Case-sensitive matching (not case-insensitive)</li>
         *   <li>Exact tag match (not prefix matching)</li>
         *   <li>Boundary check: opening tag must be at block boundary
         *       (start of text or preceded by newline + optional whitespace)</li>
         * </ul>
         *
         * @param input the raw text chunk
         * @return the text with think-block content removed
         */
        String scrub(String input) {
            if (input == null || input.isEmpty()) {
                return "";
            }

            // If we have a pending partial opening tag from the previous chunk,
            // prepend it to this chunk so the tag is reassembled correctly (Hermes parity).
            // Hermes stores partial tags in _think_buffer and prepends to the next chunk.
            if (pendingTag.length() > 0) {
                input = pendingTag.toString() + input;
                pendingTag.setLength(0);
            }

            // If we have a pending partial closing tag from the previous chunk,
            // prepend it to this chunk
            if (pendingClosingTag != null) {
                input = pendingClosingTag + input;
                pendingClosingTag = null;
            }

            // If we're inside a think block, look for the closing tag
            if (insideThinkBlock) {
                int closeIdx = findClosingTag(input);
                if (closeIdx >= 0) {
                    // Found closing tag — resume output after it
                    insideThinkBlock = false;
                    pendingTag.setLength(0);
                    int afterTag = findEndOfClosingTag(input, closeIdx);
                    return scrub(input.substring(afterTag));
                } else {
                    // Still inside think block — suppress all content
                    // But check if we have a partial closing tag at the end
                    checkPartialClosingTag(input);
                    return "";
                }
            }

            // Not inside a think block — look for opening tags at block boundaries
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                int openIdx = findOpeningTagAtBoundary(input, i);
                if (openIdx < 0) {
                    // No opening tag found — append rest, but check for partial tag at end
                    String rest = input.substring(i);
                    String[] split = splitPartialOpeningTag(rest);
                    result.append(split[0]);
                    if (split[1] != null) {
                        pendingTag.setLength(0);
                        pendingTag.append(split[1]);
                    }
                    break;
                }

                // Append content before the tag
                result.append(input, i, openIdx);

                // Find the end of the opening tag (the '>')
                int tagEnd = input.indexOf('>', openIdx);
                if (tagEnd < 0) {
                    // Opening tag is incomplete — enter think mode, suppress rest
                    insideThinkBlock = true;
                    break;
                }

                // Check if the closing tag is on the same chunk
                int closeIdx = findClosingTag(input, tagEnd + 1);
                if (closeIdx >= 0) {
                    // Full think block within this chunk — skip it
                    int afterClose = findEndOfClosingTag(input, closeIdx);
                    i = afterClose;
                } else {
                    // Enter think block mode
                    insideThinkBlock = true;
                    // Check for partial closing tag at end
                    String rest = input.substring(tagEnd + 1);
                    checkPartialClosingTag(rest);
                    break;
                }
            }

            String output = result.toString();
            accumulatedBefore += output;
            return output;
        }

        /**
         * Flush any remaining state. Hermes parity (stream_consumer.py:830):
         * if there's a pending partial opening tag that didn't turn out to be
         * a think tag, release it as visible text instead of silently dropping it.
         */
        String flush() {
            String released = "";
            if (pendingTag.length() > 0 && !insideThinkBlock) {
                // Partial tag that was never confirmed as a think tag — release it
                released = pendingTag.toString();
            }
            insideThinkBlock = false;
            pendingTag.setLength(0);
            pendingClosingTag = null;
            accumulatedBefore = "";
            return released;
        }

        /**
         * Find the earliest opening tag at a block boundary.
         * Boundary check (mirrors Hermes stream_consumer.py logic):
         * - Tag at index 0: boundary if accumulated text is empty or ends with newline
         * - Tag at index > 0: boundary if text between last newline and tag is whitespace-only
         *   (or if no newline before tag, all text before tag is whitespace AND
         *   accumulated text is empty or ends with newline)
         */
        private int findOpeningTagAtBoundary(String text, int from) {
            int earliest = -1;
            for (String tag : OPENING_TAGS) {
                int searchStart = from;
                while (true) {
                    int idx = text.indexOf(tag, searchStart);
                    if (idx < 0) break;

                    // Boundary check
                    boolean isBoundary;
                    if (idx == from) {
                        // At the start of the current chunk
                        isBoundary = accumulatedBefore.isEmpty()
                            || accumulatedBefore.endsWith("\n");
                    } else {
                        // Check text before the tag within this chunk
                        String preceding = text.substring(from, idx);
                        int lastNl = preceding.lastIndexOf('\n');
                        if (lastNl == -1) {
                            // No newline before tag in this chunk
                            isBoundary = (accumulatedBefore.isEmpty()
                                || accumulatedBefore.endsWith("\n"))
                                && preceding.strip().isEmpty();
                        } else {
                            isBoundary = preceding.substring(lastNl + 1).strip().isEmpty();
                        }
                    }

                    if (isBoundary) {
                        if (earliest < 0 || idx < earliest) {
                            earliest = idx;
                        }
                        break; // first boundary hit for this tag is enough
                    }
                    searchStart = idx + 1;
                }
            }
            return earliest;
        }

        private int findClosingTag(String text) {
            return findClosingTag(text, 0);
        }

        private int findClosingTag(String text, int from) {
            int earliest = -1;
            for (String tag : CLOSING_TAGS) {
                int idx = text.indexOf(tag, from);
                if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                    earliest = idx;
                }
            }
            return earliest;
        }

        private int findEndOfClosingTag(String text, int closeIdx) {
            // Find the '>' after the closing tag start
            int gt = text.indexOf('>', closeIdx);
            return gt >= 0 ? gt + 1 : text.length();
        }

        /**
         * Check if the end of text contains a partial closing tag like "</thin".
         * If found, store it in pendingClosingTag so it can be prepended to the
         * next chunk.
         */
        private void checkPartialClosingTag(String text) {
            for (String tag : CLOSING_TAG_PREFIXES) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 2; len--) {
                    if (text.endsWith(tag.substring(0, len))) {
                        // Partial closing tag at end — store it for the next chunk
                        pendingClosingTag = tag.substring(0, len);
                        return;
                    }
                }
            }
        }

        /**
         * Split text that may end with a partial opening tag.
         * Returns [safe_text, pending_tag_or_null].
         */
        private String[] splitPartialOpeningTag(String text) {
            for (String tag : OPENING_TAG_PREFIXES) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 1; len--) {
                    String suffix = tag.substring(0, len);
                    if (text.endsWith(suffix)) {
                        return new String[]{text.substring(0, text.length() - len), suffix};
                    }
                }
            }
            return new String[]{text, null};
        }
    }
}