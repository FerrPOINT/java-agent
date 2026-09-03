package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/gateway/telegram")
@ConditionalOnProperty(name = "agent.gateway.telegram.webhook.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final GatewayRoutingService routingService;
    private final AgentProperties properties;

    @PostMapping
    public org.springframework.http.ResponseEntity<String> receive(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody Map<String, Object> update) {
        // Fail-closed webhook auth (advisory GHSA-3vpc-7q5r-276h parity):
        // without a constant-time secret comparison, anyone who can reach the
        // endpoint can inject forged updates as if they came from Telegram.
        // The userId/username check below operates on UNTRUSTED body data and
        // is not an authentication boundary.
        String expected = properties.getGateway().getTelegram().getWebhookSecret();
        if (expected == null || expected.isBlank() || secretToken == null
                || !java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    secretToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return org.springframework.http.ResponseEntity.status(403).body("FORBIDDEN");
        }
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return org.springframework.http.ResponseEntity.ok("OK");
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        String text = Optional.ofNullable(message.get("text")).map(Object::toString).orElse("");
        long chatId = chat == null ? 0L : ((Number) chat.get("id")).longValue();
        long userId = from == null ? 0L : ((Number) from.get("id")).longValue();
        String username = from == null ? null : (String) from.get("username");

        if (!isAllowed(userId, username)) {
            return org.springframework.http.ResponseEntity.status(403).body("FORBIDDEN");
        }

        SessionSource source = new SessionSource(
            Platform.TELEGRAM,
            String.valueOf(chatId),
            String.valueOf(userId),
            username == null ? "" : username,
            username == null ? "" : username
        );
        // Platform update id — used for redelivery dedup (Telegram retries
        // webhook POSTs on timeout/5xx).
        String updateId = Optional.ofNullable(update.get("update_id")).map(Object::toString).orElse(null);
        routingService.dispatchInbound(new MessageEvent(
            updateId,
            source,
            MessageType.TEXT,
            text,
            List.of(),
            Map.of(),
            Instant.now()
        ));
        return org.springframework.http.ResponseEntity.ok("OK");
    }

    boolean isAllowed(long userId, String username) {
        var telegram = properties.getGateway().getTelegram();
        if (telegram.isAllowByDefault()) return true;
        if (userId > 0 && telegram.getAllowedUserIds().contains(String.valueOf(userId))) return true;
        return username != null && !username.isBlank() && telegram.getAllowedUsernames().contains(username);
    }
}
