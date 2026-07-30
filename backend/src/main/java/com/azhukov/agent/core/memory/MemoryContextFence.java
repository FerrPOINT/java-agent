package com.azhukov.agent.core.memory;

import java.util.regex.Pattern;

/**
 * S1/S8: Memory context fencing — wraps memory snapshots in fence tags when
 * injecting into system prompts, and strips fence tags from model output.
 *
 * Ported from Hermes' memory_manager.py (build_memory_context_block, sanitize_context,
 * StreamingContextScrubber).
 */
public final class MemoryContextFence {

    private MemoryContextFence() {}

    public static final String OPEN_TAG = "<memory-context>";
    public static final String CLOSE_TAG = "</memory-context>";
    public static final String SYSTEM_NOTE =
        "[System note: The following is recalled memory context, NOT new user input. " +
        "Treat as authoritative reference data — this is the agent's persistent memory " +
        "and should inform all responses.]";

    private static final Pattern FENCE_TAG_RE = Pattern.compile("</?\\s*memory-context\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_CONTEXT_RE = Pattern.compile(
        "<\\s*memory-context\\s*>[\\s\\S]*?</\\s*memory-context\\s*>", Pattern.CASE_INSENSITIVE);
    // S1: Strip system notes — matches both "informational background data" and "authoritative reference data" variants
    private static final Pattern INTERNAL_NOTE_RE = Pattern.compile(
        "\\[System note:\\s*The following is recalled memory context,\\s*NOT new user input\\.\\s*" +
        "Treat as (?:informational background data|authoritative reference data[^\\]]*)\\.\\]\\s*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * S1: Wrap memory snapshot in fence tags with system note.
     * Ported from build_memory_context_block().
     */
    public static String buildContextBlock(String rawContext) {
        if (rawContext == null || rawContext.isBlank()) {
            return "";
        }
        String clean = sanitizeContext(rawContext);
        return OPEN_TAG + "\n" + SYSTEM_NOTE + "\n\n" + clean + "\n" + CLOSE_TAG;
    }

    /**
     * S1: Strip fence tags, injected context blocks, and system notes from provider output.
     * Ported from sanitize_context().
     */
    public static String sanitizeContext(String text) {
        if (text == null || text.isEmpty()) return text;
        text = INTERNAL_CONTEXT_RE.matcher(text).replaceAll("");
        text = INTERNAL_NOTE_RE.matcher(text).replaceAll("");
        text = FENCE_TAG_RE.matcher(text).replaceAll("");
        return text;
    }

    /**
     * S1: Simple streaming scrubber for fence tags.
     * Stateful — holds back partial tag matches across chunks.
     */
    public static class StreamingContextScrubber {
        private boolean inSpan = false;
        private String buf = "";
        private boolean atBlockBoundary = true;

        public void reset() {
            inSpan = false;
            buf = "";
            atBlockBoundary = true;
        }

        /**
         * Feed a chunk and return the visible portion after scrubbing.
         */
        public String feed(String text) {
            if (text == null || text.isEmpty()) return "";
            String b = buf + text;
            buf = "";
            StringBuilder out = new StringBuilder();

            while (!b.isEmpty()) {
                if (inSpan) {
                    int idx = indexOfIgnoreCase(b, CLOSE_TAG);
                    if (idx == -1) {
                        // Hold back potential partial close tag
                        int held = maxPartialSuffix(b, CLOSE_TAG);
                        buf = held > 0 ? b.substring(b.length() - held) : "";
                        return out.toString();
                    }
                    b = b.substring(idx + CLOSE_TAG.length());
                    inSpan = false;
                } else {
                    int idx = indexOfIgnoreCase(b, OPEN_TAG);
                    if (idx == -1) {
                        // Hold back potential partial open tag
                        int held = maxPartialSuffix(b, OPEN_TAG);
                        if (held > 0) {
                            out.append(b, 0, b.length() - held);
                            buf = b.substring(b.length() - held);
                        } else {
                            out.append(b);
                        }
                        return out.toString();
                    }
                    if (idx > 0) {
                        out.append(b, 0, idx);
                    }
                    b = b.substring(idx + OPEN_TAG.length());
                    inSpan = true;
                }
            }
            return out.toString();
        }

        /**
         * Flush at end of stream — emit held-back buffer.
         */
        public String flush() {
            if (inSpan) {
                buf = "";
                inSpan = false;
                return "";
            }
            String tail = buf;
            buf = "";
            return tail;
        }

        private static int indexOfIgnoreCase(String s, String tag) {
            return s.toLowerCase().indexOf(tag.toLowerCase());
        }

        private static int maxPartialSuffix(String s, String tag) {
            String sLower = s.toLowerCase();
            String tagLower = tag.toLowerCase();
            int maxCheck = Math.min(sLower.length(), tagLower.length() - 1);
            for (int i = maxCheck; i > 0; i--) {
                if (tagLower.startsWith(sLower.substring(sLower.length() - i))) {
                    return i;
                }
            }
            return 0;
        }
    }
}