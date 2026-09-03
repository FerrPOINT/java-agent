package com.azhukov.agent.bot.client;

import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Package-private collaborator that handles all media upload/download
 * operations for {@link TelegramClient}. Extracted to reduce the size
 * of the main client class.
 *
 * <p>Delegates API calls back to {@link TelegramClient#callApi} and
 * {@link TelegramClient#callMultipartApi} so that rate limiting,
 * error-code tracking, and conflict detection remain centralised.
 *
 * @see TelegramClient#sendPhoto
 * @see TelegramClient#sendDocument
 * @see TelegramClient#sendVoice
 * @see TelegramClient#sendVideo
 * @see TelegramClient#sendAudioAsVoice
 * @see TelegramClient#sendMediaGroup
 * @see TelegramClient#getFile
 * @see TelegramClient#downloadFile
 */
@Slf4j
class TelegramMediaClient {

    private final TelegramClient client;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String botToken;

    TelegramMediaClient(TelegramClient client, RestClient restClient,
                         ObjectMapper objectMapper, String botToken) {
        this.client = client;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.botToken = botToken != null ? botToken : "";
    }

    // ─── Photo ────────────────────────────────────────────────────

    Optional<Long> sendPhoto(long chatId, byte[] photo, String caption, String parseMode) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("photo", new ByteArrayResource(photo) {
            @Override public String getFilename() { return "photo.jpg"; }
        }, MediaType.IMAGE_JPEG);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        try {
            return client.callMultipartApi("sendPhoto", builder.build())
                    .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendPhoto failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Document ────────────────────────────────────────────────

    Optional<Long> sendDocument(long chatId, byte[] document, String fileName,
                                String caption, String parseMode) {
        String name = (fileName == null || fileName.isBlank()) ? "document" : fileName;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("document", new ByteArrayResource(document) {
            @Override public String getFilename() { return name; }
        }, MediaType.APPLICATION_OCTET_STREAM);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        try {
            return client.callMultipartApi("sendDocument", builder.build())
                    .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendDocument failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Voice ───────────────────────────────────────────────────

    Optional<Long> sendVoice(long chatId, byte[] voice, String caption) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("voice", new ByteArrayResource(voice) {
            @Override public String getFilename() { return "voice.ogg"; }
        }, MediaType.parseMediaType("audio/ogg"));
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        try {
            return client.callMultipartApi("sendVoice", builder.build())
                    .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendVoice failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Video ────────────────────────────────────────────────────

    Optional<Long> sendVideo(long chatId, byte[] video, String fileName,
                             String caption, String parseMode) {
        String name = (fileName == null || fileName.isBlank()) ? "video.mp4" : fileName;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("video", new ByteArrayResource(video) {
            @Override public String getFilename() { return name; }
        }, MediaType.APPLICATION_OCTET_STREAM);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        try {
            return client.callMultipartApi("sendVideo", builder.build())
                    .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (TelegramApiException e) {
            log.warn("sendVideo failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Audio as Voice ───────────────────────────────────────────

    Optional<Long> sendAudioAsVoice(long chatId, byte[] audio, String fileName, String caption) {
        String name = (fileName == null || fileName.isBlank()) ? "voice.ogg" : fileName;
        // Caption ladder: MarkdownV2 first, plain retry when Telegram rejects
        // the entities. A bare '*' or '_' in a transcript must not kill the
        // voice bubble (PR contract, mirrors sendMessage parse fallback).
        TelegramApiException lastParseError = null;
        String plain = caption == null ? null : truncateUtf16(caption, 1024);
        String formatted = null;
        if (caption != null && !caption.isBlank()) {
            try {
                formatted = MarkdownConverter.convert(caption);
            } catch (Exception e) {
                log.debug("Voice caption MarkdownV2 formatting failed; sending plain caption", e);
            }
        }
        boolean tryMarkdown = formatted != null && !formatted.isBlank()
            && formatted.length() <= 1024;
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<String> modes = new java.util.ArrayList<>();
        if (tryMarkdown) {
            texts.add(formatted); modes.add("MarkdownV2");
        }
        if (plain != null) {
            texts.add(plain); modes.add(null);
        }
        if (texts.isEmpty()) {
            texts.add(null); modes.add(null);
        }
        for (int i = 0; i < texts.size(); i++) {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("chat_id", String.valueOf(chatId));
            builder.part("voice", new ByteArrayResource(audio) {
                @Override public String getFilename() { return name; }
            }, MediaType.parseMediaType("audio/ogg"));
            if (texts.get(i) != null && !texts.get(i).isBlank()) builder.part("caption", texts.get(i));
            if (modes.get(i) != null) builder.part("parse_mode", modes.get(i));
            try {
                return client.callMultipartApi("sendVoice", builder.build())
                        .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
            } catch (TelegramApiException e) {
                if (modes.get(i) != null && e.isParseError()) {
                    log.warn("sendAudioAsVoice MarkdownV2 caption rejected, retrying plain text: {}", e.getMessage());
                    lastParseError = e;
                    continue;
                }
                log.warn("sendAudioAsVoice failed: {}", e.getMessage());
                return Optional.empty();
            }
        }
        if (lastParseError != null) {
            log.warn("sendAudioAsVoice failed after caption fallback: {}", lastParseError.getMessage());
        }
        return Optional.empty();
    }

    // ─── Media Group (album) ──────────────────────────────────────

    List<Long> sendMediaGroup(long chatId, List<TelegramClient.PhotoInput> photos) {
        if (photos == null || photos.isEmpty()) {
            return List.of();
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));

        // Build the media JSON array with attach:// references
        List<Map<String, Object>> mediaArray = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            TelegramClient.PhotoInput photo = photos.get(i);
            String attachName = "photo" + i;
            String mediaType = "photo";
            // GIFs are sent as animations, not photos — but for simplicity in
            // media groups, we send them as photos (Telegram will reject .gif
            // in media groups; the caller should peel off GIFs before calling).
            MediaType mimeType = guessImageMediaType(photo.fileName());
            builder.part(attachName, new ByteArrayResource(photo.data()) {
                @Override public String getFilename() { return photo.fileName(); }
            }, mimeType);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", mediaType);
            item.put("media", "attach://" + attachName);
            if (photo.caption() != null && !photo.caption().isBlank()) {
                item.put("caption", photo.caption());
                item.put("parse_mode", "MarkdownV2");
            }
            mediaArray.add(item);
        }

        try {
            builder.part("media", objectMapper.writeValueAsString(mediaArray));
        } catch (Exception e) {
            log.warn("sendMediaGroup: failed to serialize media JSON: {}", e.getMessage());
            return List.of();
        }

        try {
            Optional<TelegramResponse> response = client.callMultipartApi("sendMediaGroup", builder.build());
            if (response.isPresent()) {
                // sendMediaGroup returns an array of Message objects
                Object result = response.get().result();
                if (result instanceof List<?> list) {
                    List<Long> messageIds = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> msg) {
                            Object id = msg.get("message_id");
                            if (id instanceof Number num) {
                                messageIds.add(num.longValue());
                            }
                        }
                    }
                    return messageIds;
                }
            }
            return List.of();
        } catch (TelegramApiException e) {
            log.warn("sendMediaGroup failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── File download ───────────────────────────────────────────

    Optional<Map<String, Object>> getFile(String fileId) {
        Map<String, Object> params = Map.of("file_id", fileId);
        try {
            return client.callApi("getFile", params).map(TelegramResponse::resultAsMap);
        } catch (TelegramApiException e) {
            log.warn("getFile failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    Optional<byte[]> downloadFile(String filePath) {
        try {
            byte[] data = restClient.get()
                .uri("https://api.telegram.org/file/bot{token}/{path}", botToken, filePath)
                .retrieve()
                .body(byte[].class);
            return Optional.ofNullable(data);
        } catch (Exception e) {
            log.warn("downloadFile failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────

    static MediaType guessImageMediaType(String fileName) {
        if (fileName == null) return MediaType.IMAGE_JPEG;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.IMAGE_JPEG;
    }

    private static String truncateUtf16(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
