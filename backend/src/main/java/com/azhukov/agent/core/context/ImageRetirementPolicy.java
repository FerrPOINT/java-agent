package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P-10 (Hermes commits b7544dba01 / 7ff2fe8bc9 / dff84f1890): ONE shared
 * image-retirement policy for every compression pass (demote pass 2, retire
 * pass 3.5, protected-tail pressure prune, fallback compaction).
 *
 * <p>Hermes keeps the newest {@value #MAX_KEEP_TOOL_IMAGES} image-bearing
 * TOOL results intact (follow-up screenshot QA still sees the latest frames)
 * and retires the rest to text placeholders. User-role uploads are never
 * touched. The single policy owner prevents the demote/retire copies from
 * diverging again (the exact bug Hermes b7544dba01 fixed upstream).</p>
 */
public final class ImageRetirementPolicy {

    /** Hermes _MAX_KEEP_TOOL_IMAGES (context_compressor.py:1231). */
    static final int MAX_KEEP_TOOL_IMAGES = 3;

    /** Placeholder template for a retired screenshot payload. */
    static final String SCREENSHOT_REMOVED = "[screenshot removed]";

    private static final Pattern IMAGE_DATA_URI = Pattern.compile(
        "data:image/[a-zA-Z+.-]+;base64,[A-Za-z0-9+/=]{200,}");

    private ImageRetirementPolicy() {
    }

    /** True when a tool-result content carries strippable image payload. */
    static boolean hasImages(Message m) {
        if (m == null || m.role() != Role.TOOL) return false;
        if (m.imageCount() != null && m.imageCount() > 0) return true;
        return m.content() != null && IMAGE_DATA_URI.matcher(m.content()).find();
    }

    /**
     * Return a copy with image payloads replaced by a short placeholder, or
     * null when there is nothing strippable. Never mutates the input.
     */
    static Message stripImages(Message m) {
        if (!hasImages(m)) return null;
        int count = m.imageCount() != null && m.imageCount() > 0
            ? m.imageCount() : countDataUris(m.content());
        String summary = m.content() != null ? textWithoutImages(m.content()) : "";
        String placeholder = SCREENSHOT_REMOVED
            + (count > 0 ? " " + count + " image" + (count > 1 ? "s" : "") : "")
            + (summary.isBlank() ? "" : " " + trimTo(summary, 200));
        return new Message(m.role(), placeholder, m.toolCall(), m.toolCalls(),
            m.toolCallId(), m.turnIndex(), 0, m.createdAt());
    }

    /**
     * Hermes _retire_stale_tool_result_images: walk NEWEST-first, keep the
     * newest {@code keepNewest} image-bearing tool messages, retire the rest.
     *
     * @return number of messages rewritten (0 = no-op)
     */
    public static int retireStaleToolResultImages(List<Message> messages, int keepNewest) {
        if (messages == null || messages.isEmpty()) return 0;
        if (keepNewest < 0) keepNewest = 0;
        int seen = 0;
        int pruned = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!hasImages(msg)) continue;
            seen++;
            if (seen <= keepNewest) continue;
            Message stripped = stripImages(msg);
            if (stripped == null) continue;
            messages.set(i, stripped);
            pruned++;
        }
        return pruned;
    }

    /** Convenience overload with the Hermes default keep-newest cap. */
    public static int retireStaleToolResultImages(List<Message> messages) {
        return retireStaleToolResultImages(messages, MAX_KEEP_TOOL_IMAGES);
    }

    private static int countDataUris(String content) {
        if (content == null) return 0;
        Matcher matcher = IMAGE_DATA_URI.matcher(content);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static String textWithoutImages(String content) {
        if (content == null) return "";
        return IMAGE_DATA_URI.matcher(content).replaceAll("");
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
