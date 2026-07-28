package com.azhukov.agent.gateway.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

@Slf4j
public class TelegramBotApiClient {

    private final RestClient restClient;
    private final String botToken;

    public TelegramBotApiClient(String botToken, RestClient restClient) {
        this.botToken = botToken == null ? "" : botToken;
        this.restClient = restClient;
    }

    public Optional<String> sendMessage(long chatId, String text) {
        return callApi("sendMessage", Map.of(
            "chat_id", chatId,
            "text", text
        ));
    }

    public Optional<String> sendDocument(long chatId, byte[] document, String fileName, String caption) {
        if (botToken.isBlank()) {
            log.warn("Telegram bot token is empty; cannot call sendDocument");
            return Optional.empty();
        }
        if (document == null || document.length == 0) {
            log.warn("Document is empty; cannot send");
            return Optional.empty();
        }
        String name = (fileName == null || fileName.isBlank()) ? "document" : fileName;
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("chat_id", String.valueOf(chatId));
            builder.part("document", new ByteArrayResource(document) {
                @Override
                public String getFilename() {
                    return name;
                }
            }, MediaType.APPLICATION_OCTET_STREAM);
            if (caption != null && !caption.isBlank()) {
                builder.part("caption", caption);
            }
            MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = builder.build();
            return callMultipartApi("sendDocument", parts);
        } catch (Exception e) {
            log.warn("Telegram sendDocument failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> sendPhoto(long chatId, byte[] image, String caption) {
        if (botToken.isBlank()) {
            log.warn("Telegram bot token is empty; cannot call sendPhoto");
            return Optional.empty();
        }
        if (image == null || image.length == 0) {
            log.warn("Image is empty; cannot send");
            return Optional.empty();
        }
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("chat_id", String.valueOf(chatId));
            builder.part("photo", new ByteArrayResource(image) {
                @Override
                public String getFilename() {
                    return "photo.jpg";
                }
            }, MediaType.IMAGE_JPEG);
            if (caption != null && !caption.isBlank()) {
                builder.part("caption", caption);
            }
            MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = builder.build();
            return callMultipartApi("sendPhoto", parts);
        } catch (Exception e) {
            log.warn("Telegram sendPhoto failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean sendChatAction(long chatId, String action) {
        return callApi("sendChatAction", Map.of("chat_id", chatId, "action", action)).isPresent();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> callApi(String method, Map<String, Object> params) {
        if (botToken.isBlank()) {
            log.warn("Telegram bot token is empty; cannot call {}", method);
            return Optional.empty();
        }
        try {
            var response = restClient.post()
                .uri("https://api.telegram.org/bot{token}/{method}", botToken, method)
                .contentType(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .toEntity(Map.class);
            return extractMessageId(method, response);
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}", method, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> callMultipartApi(String method, MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts) {
        try {
            var response = restClient.post()
                .uri("https://api.telegram.org/bot{token}/{method}", botToken, method)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(Map.class);
            return extractMessageId(method, response);
        } catch (Exception e) {
            log.warn("Telegram {} (multipart) failed: {}", method, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> extractMessageId(String method, org.springframework.http.ResponseEntity<Map> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Telegram {} returned HTTP {}", method, response.getStatusCode());
            return Optional.empty();
        }
        Boolean ok = (Boolean) response.getBody().get("ok");
        if (!Boolean.TRUE.equals(ok)) {
            log.warn("Telegram {} error: {}", method, response.getBody().get("description"));
            return Optional.empty();
        }
        Object result = response.getBody().get("result");
        if (result instanceof Map<?, ?> resultMap) {
            Object messageId = resultMap.get("message_id");
            return Optional.ofNullable(messageId).map(Object::toString);
        }
        return Optional.empty();
    }
}