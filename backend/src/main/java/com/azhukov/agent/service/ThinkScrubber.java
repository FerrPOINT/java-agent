package com.azhukov.agent.service;

import java.util.List;

/**
 * Strips reasoning/thinking blocks from streaming output.
 * <p>
 * Supports 5 tag variants (case-insensitive):
 * <ul>
 *   <li>{@code <think>...<think>} (existing)</li>
 *   <li>{@code <thinking>...</thinking>}</li>
 *   <li>{@code <reasoning>...</reasoning>}</li>
 *   <li>{@code <thought>...</thought>}</li>
 *   <li>{@code <REASONING_SCRATCHPAD>...</REASONING_SCRATCHPAD>}</li>
 * </ul>
 * <p>
 * Block-boundary rule: opening tags are only treated as block openers when they
 * appear at the start of the stream, after a newline, or when only whitespace
 * has been emitted on the current line. This prevents prose that mentions the
 * tag name (e.g. "use <think> tags here") from being incorrectly suppressed.
 * Closed pairs ({@code <tag>X</tag>}) are always suppressed regardless of boundary.
 * <p>
 * This class is stateful and <strong>not thread-safe</strong>. A new instance
 * must be created for each stream via {@code new ThinkScrubber()}.
 */
public class ThinkScrubber {

    // XML-style tag names (the text between < and >)
    private static final List<String> XML_TAG_NAMES = List.of(
        "think", "thinking", "reasoning", "thought", "REASONING_SCRATCHPAD"
    );

    // Bare format tags (no angle brackets, used by some models)
    private static final List<String> BARE_OPEN_TAGS = List.of();
    private static final List<String> BARE_CLOSE_TAGS = List.of();

    // All open tags: XML-style (<tag>) + bare format (tag)
    private static final List<String> OPEN_TAGS;
    private static final List<String> CLOSE_TAGS;
    static {
        var xmlOpen = XML_TAG_NAMES.stream().map(n -> "<" + n + ">").toList();
        var xmlClose = XML_TAG_NAMES.stream().map(n -> "</" + n + ">").toList();
        OPEN_TAGS = new java.util.ArrayList<>();
        OPEN_TAGS.addAll(xmlOpen);
        OPEN_TAGS.addAll(BARE_OPEN_TAGS);
        CLOSE_TAGS = new java.util.ArrayList<>();
        CLOSE_TAGS.addAll(xmlClose);
        CLOSE_TAGS.addAll(BARE_CLOSE_TAGS);
    }
    private static final int MAX_TAG_LEN = OPEN_TAGS.stream().mapToInt(String::length).max().orElse(7);

    private boolean inBlock = false;
    private final StringBuilder buffer = new StringBuilder();
    private boolean lastEmittedEndedNewline = true;

    /**
     * Process a chunk of streaming output, returning only the visible (non-think) portion.
     * Handles cases where tags are split across chunks.
     *
     * @param chunk the incoming chunk
     * @return the visible content extracted from this chunk; may be empty
     */
    public String scrub(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        buffer.append(chunk);
        StringBuilder result = new StringBuilder();

        while (buffer.length() > 0) {
            if (inBlock) {
                // Look for closing tag (case-insensitive)
                int[] closeMatch = findFirstTag(buffer, CLOSE_TAGS);
                int closeIdx = closeMatch[0];
                int closeLen = closeMatch[1];
                if (closeIdx >= 0) {
                    buffer.delete(0, closeIdx + closeLen);
                    inBlock = false;
                } else {
                    int partialLen = maxPartialSuffix(buffer, CLOSE_TAGS);
                    if (partialLen > 0) {
                        buffer.delete(0, buffer.length() - partialLen);
                    } else {
                        buffer.setLength(0);
                    }
                    break;
                }
            } else {
                // Priority 1: closed <tag>...</tag> pair anywhere in buffer (no boundary gating)
                int[] pairMatch = findEarliestClosedPair(buffer);
                // Priority 2: unterminated open tag at block boundary
                int[] openMatch = findOpenAtBoundary(buffer, result);

                // Pick whichever match comes earliest
                if (pairMatch[0] >= 0 && (openMatch[0] < 0 || pairMatch[0] <= openMatch[0])) {
                    // Closed pair — emit preceding, strip pair
                    int startIdx = pairMatch[0];
                    int endIdx = pairMatch[1];
                    String preceding = buffer.substring(0, startIdx);
                    if (!preceding.isEmpty()) {
                        String stripped = stripOrphanCloseTags(preceding);
                        if (!stripped.isEmpty()) {
                            result.append(stripped);
                            lastEmittedEndedNewline = stripped.endsWith("\n");
                        }
                    }
                    buffer.delete(0, endIdx);
                    continue;
                }

                if (openMatch[0] >= 0) {
                    // Unterminated open at boundary — emit preceding, enter block
                    int openIdx = openMatch[0];
                    int openLen = openMatch[1];
                    String preceding = buffer.substring(0, openIdx);
                    if (!preceding.isEmpty()) {
                        String stripped = stripOrphanCloseTags(preceding);
                        if (!stripped.isEmpty()) {
                            result.append(stripped);
                            lastEmittedEndedNewline = stripped.endsWith("\n");
                        }
                    }
                    inBlock = true;
                    buffer.delete(0, openIdx + openLen);
                    continue;
                }

                // No resolvable tag structure — hold back partial tag suffix, emit rest
                int held = maxPartialSuffix(buffer, OPEN_TAGS);
                int heldClose = maxPartialSuffix(buffer, CLOSE_TAGS);
                held = Math.max(held, heldClose);
                if (held > 0) {
                    String emitText = buffer.substring(0, buffer.length() - held);
                    buffer.delete(0, buffer.length() - held);
                    if (!emitText.isEmpty()) {
                        emitText = stripOrphanCloseTags(emitText);
                        if (!emitText.isEmpty()) {
                            result.append(emitText);
                            lastEmittedEndedNewline = emitText.endsWith("\n");
                        }
                    }
                } else {
                    String emitText = buffer.toString();
                    buffer.setLength(0);
                    if (!emitText.isEmpty()) {
                        emitText = stripOrphanCloseTags(emitText);
                        if (!emitText.isEmpty()) {
                            result.append(emitText);
                            lastEmittedEndedNewline = emitText.endsWith("\n");
                        }
                    }
                }
                break;
            }
        }

        return result.toString();
    }

    /**
     * Called at end of stream. Returns any remaining buffered visible content.
     * If still inside a think block (unclosed), the buffered content is discarded.
     */
    public String flush() {
        if (inBlock) {
            buffer.setLength(0);
            inBlock = false;
            return "";
        }
        String remaining = buffer.toString();
        buffer.setLength(0);
        if (remaining.isEmpty()) {
            return "";
        }
        remaining = stripOrphanCloseTags(remaining);
        if (!remaining.isEmpty()) {
            lastEmittedEndedNewline = remaining.endsWith("\n");
        }
        return remaining;
    }

    /**
     * Reset all state. Call at the top of every new turn.
     */
    public void reset() {
        inBlock = false;
        buffer.setLength(0);
        lastEmittedEndedNewline = true;
    }

    // ── Internal helpers ──────────────────────────────────────────

    /**
     * Find the earliest tag in the buffer (case-insensitive).
     * Returns [index, length] or [-1, 0] if not found.
     */
    private int[] findFirstTag(StringBuilder buf, List<String> tags) {
        String bufLower = buf.toString().toLowerCase();
        int bestIdx = -1;
        int bestLen = 0;
        for (String tag : tags) {
            String tagLower = tag.toLowerCase();
            int idx = bufLower.indexOf(tagLower);
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx;
                bestLen = tag.length();
            }
        }
        return new int[]{bestIdx, bestLen};
    }

    /**
     * Find the earliest closed <tag>...</tag> pair in the buffer.
     * Returns [startIdx, endIdx] or [-1, -1] if not found.
     */
    private int[] findEarliestClosedPair(StringBuilder buf) {
        String bufLower = buf.toString().toLowerCase();
        int bestStart = -1;
        int bestEnd = -1;
        for (int i = 0; i < OPEN_TAGS.size(); i++) {
            String openTag = OPEN_TAGS.get(i);
            String closeTag = CLOSE_TAGS.get(i);
            String openLower = openTag.toLowerCase();
            String closeLower = closeTag.toLowerCase();
            int openIdx = bufLower.indexOf(openLower);
            if (openIdx == -1) continue;
            int closeIdx = bufLower.indexOf(closeLower, openIdx + openLower.length());
            if (closeIdx == -1) continue;
            int endIdx = closeIdx + closeLower.length();
            if (bestStart == -1 || openIdx < bestStart) {
                bestStart = openIdx;
                bestEnd = endIdx;
            }
        }
        return new int[]{bestStart, bestEnd};
    }

    /**
     * Find the earliest block-boundary open tag in the buffer.
     * Returns [index, length] or [-1, 0].
     */
    private int[] findOpenAtBoundary(StringBuilder buf, StringBuilder alreadyEmitted) {
        String bufLower = buf.toString().toLowerCase();
        int bestIdx = -1;
        int bestLen = 0;
        for (String tag : OPEN_TAGS) {
            String tagLower = tag.toLowerCase();
            int searchStart = 0;
            while (true) {
                int idx = bufLower.indexOf(tagLower, searchStart);
                if (idx == -1) break;
                if (isBlockBoundary(buf, idx, alreadyEmitted)) {
                    if (bestIdx == -1 || idx < bestIdx) {
                        bestIdx = idx;
                        bestLen = tag.length();
                    }
                    break;
                }
                searchStart = idx + 1;
            }
        }
        return new int[]{bestIdx, bestLen};
    }

    /**
     * Check if position idx in buf is a block boundary.
     * A block boundary is:
     * - buf position 0 AND the most recent emission ended with a newline (or nothing emitted yet)
     * - any position whose preceding text on the current line is whitespace-only,
     *   AND if there is no newline in the preceding buf portion, the most recent
     *   prior emission ended with a newline.
     */
    private boolean isBlockBoundary(StringBuilder buf, int idx, StringBuilder alreadyEmitted) {
        if (idx == 0) {
            if (alreadyEmitted.length() > 0) {
                return alreadyEmitted.charAt(alreadyEmitted.length() - 1) == '\n';
            }
            return lastEmittedEndedNewline;
        }
        String preceding = buf.substring(0, idx);
        int lastNl = preceding.lastIndexOf('\n');
        if (lastNl == -1) {
            // No newline in buf before tag — boundary only if prior emission ended with newline
            // AND everything since is whitespace
            boolean priorNewline;
            if (alreadyEmitted.length() > 0) {
                priorNewline = alreadyEmitted.charAt(alreadyEmitted.length() - 1) == '\n';
            } else {
                priorNewline = lastEmittedEndedNewline;
            }
            return priorNewline && preceding.strip().isEmpty();
        }
        // Newline present — text between it and the tag must be whitespace-only
        return preceding.substring(lastNl + 1).strip().isEmpty();
    }

    /**
     * Return the longest buffer suffix that is a prefix of any tag (case-insensitive).
     * Only prefixes strictly shorter than the tag itself count.
     */
    private int maxPartialSuffix(StringBuilder buf, List<String> tags) {
        if (buffer.length() == 0) return 0;
        String bufLower = buf.toString().toLowerCase();
        int maxCheck = Math.min(bufLower.length(), MAX_TAG_LEN - 1);
        for (int i = maxCheck; i > 0; i--) {
            String suffix = bufLower.substring(bufLower.length() - i);
            for (String tag : tags) {
                String tagLower = tag.toLowerCase();
                if (tagLower.length() > i && tagLower.startsWith(suffix)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /**
     * Remove orphan close tags from text (close tags with no matching open).
     */
    private String stripOrphanCloseTags(String text) {
        if (!text.contains("</")) {
            return text;
        }
        String textLower = text.toLowerCase();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            boolean matched = false;
            if (i + 1 < text.length() && textLower.charAt(i) == '<' && textLower.charAt(i + 1) == '/') {
                for (String tag : CLOSE_TAGS) {
                    String tagLower = tag.toLowerCase();
                    int tagLen = tagLower.length();
                    if (i + tagLen <= text.length() && textLower.substring(i, i + tagLen).equals(tagLower)) {
                        // Skip the tag and trailing whitespace
                        int j = i + tagLen;
                        while (j < text.length() && (text.charAt(j) == ' ' || text.charAt(j) == '\t'
                            || text.charAt(j) == '\n' || text.charAt(j) == '\r')) {
                            j++;
                        }
                        i = j;
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}