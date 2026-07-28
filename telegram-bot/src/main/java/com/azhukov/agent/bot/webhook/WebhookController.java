package com.azhukov.agent.bot.webhook;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Consumer;

@RestController
@ConditionalOnProperty(name = "bot.mode", havingValue = "webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final WebhookSecretValidator secretValidator;
    private final ObjectMapper objectMapper;
    private final Consumer<UpdateEvent> updateHandler;

    @PostMapping("/webhook/telegram")
    public ResponseEntity<String> receive(
            @RequestHeader(value = SECRET_HEADER, required = false) String secretHeader,
            @RequestBody String body) {

        if (!secretValidator.isConfigured()) {
            log.error("Webhook secret not configured, rejecting request (fail-closed)");
            return ResponseEntity.status(403).body("Webhook secret not configured");
        }
        if (!secretValidator.isValid(secretHeader)) {
            log.warn("Webhook secret validation failed");
            return ResponseEntity.status(403).body("FORBIDDEN");
        }

        try {
            Map<String, Object> update = objectMapper.readValue(body, new TypeReference<>() {});
            UpdateEvent event = UpdateEvent.from(update);
            updateHandler.accept(event);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing webhook update: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("ERROR");
        }
    }
}