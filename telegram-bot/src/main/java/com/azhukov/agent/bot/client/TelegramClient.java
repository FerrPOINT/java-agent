package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed wrapper around the Telegram Bot API.
 * Supports sendMessage, editMessageText, deleteMessage, sendChatAction,
 * sendPhoto, sendDocument, sendVoice, getFile, answerCallbackQuery, setMyCommands.
 */
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String botToken;

    public TelegramClient(RestClient restClient, ObjectMapper objectMapper, String botToken) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.botToken = botToken != null ? botToken : "";
    }

    // ─── Text messages ────────────────────────────────────────────

    public Optional<Long> sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null, null, null);
    }

    public Optional<Long> sendMessage(long chatId, String text, String parseMode,
                                       Long replyToMessageId, String replyMarkup) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("text", text);
        if (parseMode != null && !parseMode.isBlank()) params.put("parse_mode", parseMode);
        if (replyToMessageId != null) params.put("reply_to_message_id", replyToMessageId);
        if (replyMarkup != null && !replyMarkup.isBlank()) params.put("reply_markup", replyMarkup);
        return callApi("sendMessage", params).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
    }

    public boolean editMessageText(long chatId, long messageId, String text, String parseMode) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        params.put("message_id", messageId);
        params.put("text", text);
        if (parseMode != null && !parseMode.isBlank()) params.put("parse_mode", parseMode);
        return callApi("editMessageText", params).isPresent();
    }

    public boolean deleteMessage(long chatId, long messageId) {
        Map<String, Object> params = Map.of("chat_id", chatId, "message_id", messageId);
        return callApi("deleteMessage", params).isPresent();
    }

    // ─── Chat actions ─────────────────────────────────────────────

    public boolean sendChatAction(long chatId, String action) {
        Map<String, Object> params = Map.of("chat_id", chatId, "action", action);
        return callApi("sendChatAction", params).isPresent();
    }

    public boolean sendTyping(long chatId) {
        return sendChatAction(chatId, "typing");
    }

    // ─── Media ────────────────────────────────────────────────────

    public Optional<Long> sendPhoto(long chatId, byte[] photo, String caption, String parseMode) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("photo", new ByteArrayResource(photo) {
            @Override public String getFilename() { return "photo.jpg"; }
        }, MediaType.IMAGE_JPEG);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        return callMultipartApi("sendPhoto", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
    }

    public Optional<Long> sendDocument(long chatId, byte[] document, String fileName,
                                        String caption, String parseMode) {
        String name = (fileName == null || fileName.isBlank()) ? "document" : fileName;
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("document", new ByteArrayResource(document) {
            @Override public String getFilename() { return name; }
        }, MediaType.APPLICATION_OCTET_STREAM);
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        if (parseMode != null && !parseMode.isBlank()) builder.part("parse_mode", parseMode);
        return callMultipartApi("sendDocument", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
    }

    public Optional<Long> sendVoice(long chatId, byte[] voice, String caption) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", String.valueOf(chatId));
        builder.part("voice", new ByteArrayResource(voice) {
            @Override public String getFilename() { return "voice.ogg"; }
        }, MediaType.parseMediaType("audio/ogg"));
        if (caption != null && !caption.isBlank()) builder.part("caption", caption);
        return callMultipartApi("sendVoice", builder.build()).flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
    }

    // ─── File download ────────────────────────────────────────────

    public Optional<Map<String, Object>> getFile(String fileId) {
        Map<String, Object> params = Map.of("file_id", fileId);
        return callApi("getFile", params).map(TelegramResponse::resultAsMap);
    }

    public Optional<byte[]> downloadFile(String filePath) {
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

    // ─── Callback queries ─────────────────────────────────────────

    public boolean answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) params.put("text", text);
        params.put("show_alert", showAlert);
        return callApi("answerCallbackQuery", params).isPresent();
    }

    // ─── Commands registration ────────────────────────────────────

    public boolean setMyCommands(List<Map<String, String>> commands) {
        try {
            String commandsJson = objectMapper.writeValueAsString(commands);
            Map<String, Object> params = Map.of("commands", commandsJson);
            return callApi("setMyCommands", params).isPresent();
        } catch (Exception e) {
            log.warn("setMyCommands failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Webhook management ───────────────────────────────────────

    public boolean setWebhook(String url, String secretToken) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("url", url != null ? url : "");
        if (secretToken != null && !secretToken.isBlank()) {
            params.put("secret_token", secretToken);
        }
        return callApi("setWebhook", params).isPresent();
    }

    public boolean deleteWebhook() {
        return callApi("deleteWebhook", Map.of()).isPresent();
    }

    public Optional<Map<String, Object>> getWebhookInfo() {
        return callApi("getWebhookInfo", Map.of()).map(TelegramResponse::resultAsMap);
    }

    // ─── getUpdates (long polling) ────────────────────────────────

    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> getUpdates(long offset, int limit, int timeoutSeconds) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("offset", offset);
        params.put("limit", limit);
        params.put("timeout", timeoutSeconds);
        return callApi("getUpdates", params).map(resp -> {
            Object result = resp.result();
            if (result instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.<Map<String, Object>>of();
        });
    }

    // ─── Internal ─────────────────────────────────────────────────

    Optional<TelegramResponse> callApi(String method, Map<String, Object> params) {
        if (botToken.isBlank()) {
            log.warn("Bot token is empty; cannot call {}", method);
            return Optional.empty();
        }
        try {
            TelegramResponse response = restClient.post()
                .uri("/bot{token}/{method}", botToken, method)
                .contentType(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .body(TelegramResponse.class);
            if (response == null || !response.isSuccess()) {
                log.warn("Telegram {} failed: {}", method, response != null ? response.errorMessage() : "null response");
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (Exception e) {
            log.warn("Telegram {} exception: {}", method, e.getMessage());
            return Optional.empty();
        }
    }

    Optional<TelegramResponse> callMultipartApi(String method, MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts) {
        if (botToken.isBlank()) {
            log.warn("Bot token is empty; cannot call {}", method);
            return Optional.empty();
        }
        try {
            TelegramResponse response = restClient.post()
                .uri("/bot{token}/{method}", botToken, method)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(TelegramResponse.class);
            if (response == null || !response.isSuccess()) {
                log.warn("Telegram {} (multipart) failed: {}", method, response != null ? response.errorMessage() : "null response");
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (Exception e) {
            log.warn("Telegram {} (multipart) exception: {}", method, e.getMessage());
            return Optional.empty();
        }
    }
}