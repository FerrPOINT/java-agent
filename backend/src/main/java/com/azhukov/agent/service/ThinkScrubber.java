package com.azhukov.agent.service;

/**
 * Strips {@code <think>...</think>} blocks from streaming output.
 * <p>
 * This class is stateful and <strong>not thread-safe</strong>. A new instance
 * must be created for each stream via {@code new ThinkScrubber()}.
 */
public class ThinkScrubber {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private boolean insideThink = false;
    private final StringBuilder buffer = new StringBuilder();

    /**
     * Process a chunk of streaming output, returning only the visible (non-think) portion.
     * Handles cases where {@code <think>} or {@code </think>} tags are split across chunks.
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
            if (insideThink) {
                // Look for closing tag
                int closeIdx = findTag(buffer, CLOSE_TAG);
                if (closeIdx >= 0) {
                    // Found closing tag — discard everything up to and including it
                    buffer.delete(0, closeIdx + CLOSE_TAG.length());
                    insideThink = false;
                } else {
                    // Might have partial closing tag at the end — check for partial match
                    int partialLen = partialTagLength(buffer, CLOSE_TAG);
                    if (partialLen > 0) {
                        // Remove the non-partial portion, keep the partial tag in buffer
                        buffer.delete(0, buffer.length() - partialLen);
                    } else {
                        // No partial match — discard everything
                        buffer.setLength(0);
                    }
                    break;
                }
            } else {
                // Look for opening tag
                int openIdx = findTag(buffer, OPEN_TAG);
                if (openIdx >= 0) {
                    // Append everything before the tag as visible content
                    result.append(buffer, 0, openIdx);
                    buffer.delete(0, openIdx + OPEN_TAG.length());
                    insideThink = true;
                } else {
                    // Check for partial opening tag at the end
                    int partialLen = partialTagLength(buffer, OPEN_TAG);
                    if (partialLen > 0) {
                        // Append everything except the partial tag
                        result.append(buffer, 0, buffer.length() - partialLen);
                        buffer.delete(0, buffer.length() - partialLen);
                    } else {
                        // No partial match — entire buffer is visible
                        result.append(buffer);
                        buffer.setLength(0);
                    }
                    break;
                }
            }
        }

        return result.toString();
    }

    /**
     * Called at end of stream. Returns any remaining buffered visible content.
     * If we're still inside a think block (unclosed), the buffered content is discarded.
     *
     * @return any remaining visible content; may be empty
     */
    public String flush() {
        if (insideThink) {
            // Unclosed think block — discard remaining buffered content
            buffer.setLength(0);
            return "";
        }
        String remaining = buffer.toString();
        buffer.setLength(0);
        return remaining;
    }

    /**
     * Find the index of a complete tag in the buffer, or -1 if not found.
     */
    private int findTag(StringBuilder buf, String tag) {
        return buf.indexOf(tag);
    }

    /**
     * Check if the buffer ends with a partial prefix of the given tag.
     * Returns the length of the partial match, or 0 if no partial match.
     * For example, if buffer ends with "&lt;th" and tag is "&lt;think&gt;", returns 3.
     */
    private int partialTagLength(StringBuilder buf, String tag) {
        int maxPartial = Math.min(tag.length() - 1, buf.length());
        for (int len = maxPartial; len > 0; len--) {
            // Check if the last 'len' characters of buf match the first 'len' characters of tag
            boolean match = true;
            for (int i = 0; i < len; i++) {
                if (buf.charAt(buf.length() - len + i) != tag.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return len;
            }
        }
        return 0;
    }
}