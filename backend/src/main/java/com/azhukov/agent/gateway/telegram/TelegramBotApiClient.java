package com.azhukov.agent.gateway.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

public class TelegramBotApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotApiClient.class);
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
        log.warn("Multipart document upload not implemented yet");
        return Optional.empty();
    }

    public Optional<String> sendPhoto(long chatId, byte[] image, String caption) {
        log.warn("Multipart photo upload not implemented yet");
        return Optional.empty();
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
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}", method, e.getMessage());
            return Optional.empty();
        }
    }
}
