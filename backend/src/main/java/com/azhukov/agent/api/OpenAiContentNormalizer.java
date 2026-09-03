package com.azhukov.agent.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes OpenAI chat message content into the string shape used by the
 * current Java agent core.
 */
public final class OpenAiContentNormalizer {

    static final int MAX_NORMALIZED_TEXT_LENGTH = 65_536;
    static final int MAX_CONTENT_LIST_SIZE = 1_000;
    static final int MAX_IMAGE_URL_TEXT_LENGTH = 2_048;
    static final int MAX_DATA_URL_HEADER_TEXT_LENGTH = 128;

    private static final Set<String> TEXT_PART_TYPES = Set.of("text", "input_text", "output_text");
    private static final Set<String> IMAGE_PART_TYPES = Set.of("image_url", "input_image");
    private static final Set<String> FILE_PART_TYPES = Set.of("file", "input_file");

    private OpenAiContentNormalizer() {
    }

    public record NormalizedConversationContent(String text, int imageCount) {
        boolean hasVisiblePayload() {
            return (text != null && !text.isBlank()) || imageCount > 0;
        }
    }

    public static String normalizeSystemText(Object content) {
        return normalizeChatContent(content, 0);
    }

    public static String normalizeConversationText(Object content) {
        return normalizeConversationContent(content).text();
    }

    public static NormalizedConversationContent normalizeConversationContent(Object content) {
        if (content == null) {
            return new NormalizedConversationContent("", 0);
        }
        if (content instanceof String text) {
            return new NormalizedConversationContent(truncate(text), 0);
        }
        if (!(content instanceof List<?> list)) {
            return new NormalizedConversationContent(normalizeChatContent(content, 0), 0);
        }

        StringBuilder normalized = new StringBuilder();
        int count = 0;
        int imageCount = 0;
        for (Object part : list) {
            if (count++ >= MAX_CONTENT_LIST_SIZE || normalized.length() >= MAX_NORMALIZED_TEXT_LENGTH) {
                break;
            }
            if (part instanceof String text) {
                appendPart(normalized, text);
                continue;
            }
            if (!(part instanceof Map<?, ?> map)) {
                continue;
            }

            String type = partType(map);
            if (TEXT_PART_TYPES.contains(type)) {
                Object text = map.get("text");
                if (text != null) {
                    appendPart(normalized, String.valueOf(text));
                }
                continue;
            }
            if (IMAGE_PART_TYPES.contains(type)) {
                ImagePart image = imagePart(map);
                appendPart(normalized, image.asText());
                imageCount++;
                continue;
            }
            if (FILE_PART_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                    "Inline image inputs are supported, but uploaded files and document inputs are not supported on this endpoint.");
            }
            throw new IllegalArgumentException(
                "Unsupported content part type '" + type + "'. Only text and image_url/input_image parts are supported.");
        }
        return new NormalizedConversationContent(truncate(normalized.toString()), imageCount);
    }

    static boolean hasVisibleText(Object content) {
        return normalizeConversationContent(content).hasVisiblePayload();
    }

    static String errorCode(IllegalArgumentException e) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        if (message.startsWith("Image parts must include")
                || message.startsWith("Image inputs must use")) {
            return "invalid_image_url";
        }
        if (message.startsWith("Inline image inputs")
                || message.startsWith("Only image data URLs")
                || message.startsWith("Unsupported content part type")) {
            return "unsupported_content_type";
        }
        return "invalid_content_part";
    }

    private static String normalizeChatContent(Object content, int depth) {
        if (depth > 10 || content == null) {
            return "";
        }
        if (content instanceof String text) {
            return truncate(text);
        }
        if (content instanceof List<?> list) {
            StringBuilder normalized = new StringBuilder();
            int count = 0;
            for (Object part : list) {
                if (count++ >= MAX_CONTENT_LIST_SIZE || normalized.length() >= MAX_NORMALIZED_TEXT_LENGTH) {
                    break;
                }
                if (part instanceof String text) {
                    appendPart(normalized, text);
                } else if (part instanceof Map<?, ?> map) {
                    String type = partType(map);
                    if (TEXT_PART_TYPES.contains(type)) {
                        Object text = map.get("text");
                        if (text != null) {
                            appendPart(normalized, String.valueOf(text));
                        }
                    }
                } else if (part instanceof List<?>) {
                    appendPart(normalized, normalizeChatContent(part, depth + 1));
                }
            }
            return truncate(normalized.toString());
        }
        return truncate(String.valueOf(content));
    }

    private static String partType(Map<?, ?> map) {
        Object rawType = map.get("type");
        return rawType == null ? "" : String.valueOf(rawType).trim().toLowerCase(Locale.ROOT);
    }

    private static ImagePart imagePart(Map<?, ?> map) {
        Object detail = map.get("detail");
        Object imageRef = map.get("image_url");
        if (imageRef instanceof Map<?, ?> imageMap) {
            Object nestedDetail = imageMap.get("detail");
            detail = nestedDetail != null ? nestedDetail : detail;
            imageRef = imageMap.get("url");
        }
        if (!(imageRef instanceof String rawUrl) || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Image parts must include a non-empty image URL.");
        }
        String url = rawUrl.trim();
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith("data:")) {
            if (!lowerUrl.startsWith("data:image/") || !url.contains(",")) {
                throw new IllegalArgumentException(
                    "Only image data URLs are supported. Non-image data payloads are not supported.");
            }
        } else if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Image inputs must use http(s) URLs or data:image/... URLs.");
        }
        String normalizedDetail = null;
        if (detail != null) {
            if (!(detail instanceof String rawDetail) || rawDetail.isBlank()) {
                throw new IllegalArgumentException("Image detail must be a non-empty string when provided.");
            }
            normalizedDetail = rawDetail.trim();
        }
        return new ImagePart(url, normalizedDetail);
    }

    private record ImagePart(String url, String detail) {
        String asText() {
            String displayUrl = displayUrl(url);
            if (detail == null || detail.isBlank()) {
                return "[image_url: " + displayUrl + "]";
            }
            return "[image_url: " + displayUrl + ", detail: " + scrubTextToken(detail) + "]";
        }
    }

    private static String displayUrl(String url) {
        String cleanUrl = scrubTextToken(url);
        String lowerUrl = cleanUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.startsWith("data:image/")) {
            int comma = cleanUrl.indexOf(',');
            String header = comma >= 0 ? cleanUrl.substring(0, comma) : "data:image";
            if (header.length() > MAX_DATA_URL_HEADER_TEXT_LENGTH) {
                header = header.substring(0, MAX_DATA_URL_HEADER_TEXT_LENGTH);
            }
            return header + ",<redacted>";
        }
        return cleanUrl.length() > MAX_IMAGE_URL_TEXT_LENGTH
            ? cleanUrl.substring(0, MAX_IMAGE_URL_TEXT_LENGTH) + "...<truncated>"
            : cleanUrl;
    }

    private static String scrubTextToken(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\0', ' ')
            .trim();
    }

    private static void appendPart(StringBuilder target, String part) {
        if (part == null || part.isEmpty() || target.length() >= MAX_NORMALIZED_TEXT_LENGTH) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('\n');
        }
        int remaining = MAX_NORMALIZED_TEXT_LENGTH - target.length();
        if (remaining <= 0) {
            return;
        }
        target.append(part, 0, Math.min(part.length(), remaining));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_NORMALIZED_TEXT_LENGTH
            ? text.substring(0, MAX_NORMALIZED_TEXT_LENGTH)
            : text;
    }
}
