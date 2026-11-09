package com.azhukov.agent.bot.media;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses published artifact references from an assistant response without filesystem access. */
@Service
public class MediaDeliveryService {
    public static final int MAX_MEDIA_GROUP_SIZE = 10;
    public static final int MAX_MEDIA_GROUP_BYTES = 20 * 1024 * 1024;
    private static final Pattern REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])MEDIA:artifact:(att_[a-f0-9]{32})(?![A-Za-z0-9_])");
    private static final Pattern FENCED_CODE_BLOCK_RE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE_RE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern BLOCKQUOTE_RE = Pattern.compile("(?m)^\\s*>[^\\n]*");
    private static final Pattern JSON_STRING_VALUE_RE = Pattern.compile(":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final String AUDIO_AS_VOICE = "[[audio_as_voice]]";
    private static final String AS_DOCUMENT = "[[as_document]]";

    public record MediaDescriptor(String artifactId, boolean asVoice, boolean asDocument) {}
    public record ExtractionResult(List<MediaDescriptor> media, String cleanedText) {}

    public ExtractionResult extractMediaTags(String content) {
        if (content == null || content.isEmpty()) return new ExtractionResult(List.of(), "");
        boolean voice = content.contains(AUDIO_AS_VOICE);
        boolean document = content.contains(AS_DOCUMENT);
        String text = content.replace(AUDIO_AS_VOICE, "").replace(AS_DOCUMENT, "");
        Matcher matcher = REFERENCE.matcher(maskJsonStringMedia(maskProtectedSpans(text)));
        var media = new LinkedHashSet<MediaDescriptor>();
        var cleaned = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            cleaned.append(text, last, matcher.start());
            media.add(new MediaDescriptor(matcher.group(1), voice, document));
            last = matcher.end();
        }
        return new ExtractionResult(List.copyOf(media),
            cleaned.append(text, last, text.length()).toString().strip());
    }

    public String stripMediaTagsForDisplay(String text) {
        return stripMediaTagsForDisplay(text, false);
    }

    public String stripMediaTagsForDisplay(String text, boolean streaming) {
        if (streaming && text != null) {
            int start = text.lastIndexOf("MEDIA:");
            if (start >= 0 && text.substring(start).matches("MEDIA:[a-z0-9_:]*")) {
                text = text.substring(0, start);
            }
        }
        return extractMediaTags(text).cleanedText();
    }

    static String maskProtectedSpans(String content) {
        char[] chars = content.toCharArray();
        int n = chars.length;

        List<int[]> spans = new ArrayList<>();

        // Fenced code blocks: ```...\n...```
        Matcher fenced = FENCED_CODE_BLOCK_RE.matcher(content);
        while (fenced.find()) {
            spans.add(new int[]{fenced.start(), fenced.end()});
        }

        Matcher inline = INLINE_CODE_RE.matcher(content);
        while (inline.find()) {
            spans.add(new int[]{inline.start(), inline.end()});
        }

        // Blockquote lines: > at line start
        Matcher blockquote = BLOCKQUOTE_RE.matcher(content);
        while (blockquote.find()) {
            spans.add(new int[]{blockquote.start(), blockquote.end()});
        }

        // Apply masking (preserve newlines)
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1] && i < n; i++) {
                if (chars[i] != '\n') {
                    chars[i] = ' ';
                }
            }
        }

        return new String(chars);
    }

    /**
     * Mask media references inside JSON string values
     * to prevent re-delivery of stored tool-result text.
     *
     * <p>Ported from Hermes {@code _mask_json_string_media()}.
     */
    static String maskJsonStringMedia(String content) {
        if (!content.contains("\"") || !content.contains("MEDIA:")) {
            return content;
        }
        char[] chars = content.toCharArray();
        Matcher m = JSON_STRING_VALUE_RE.matcher(content);
        while (m.find()) {
            String seg = m.group(1);
            if (seg != null && seg.contains("MEDIA:")) {
                // Mask the string body (group 1), preserving newlines
                for (int i = m.start(1); i < m.end(1) && i < chars.length; i++) {
                    if (chars[i] != '\n') {
                        chars[i] = ' ';
                    }
                }
            }
        }
        return new String(chars);
    }

}
