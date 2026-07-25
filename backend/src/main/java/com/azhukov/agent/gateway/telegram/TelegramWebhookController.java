package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/gateway/telegram")
@ConditionalOnProperty(name = "agent.gateway.telegram.webhook.enabled", havingValue = "true")
public class TelegramWebhookController {

    private final GatewayRoutingService routingService;

    public TelegramWebhookController(GatewayRoutingService routingService) {
        this.routingService = routingService;
    }

    @PostMapping
    public String receive(@RequestBody Map<String, Object> update) {
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) return "OK";
        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        String text = Optional.ofNullable(message.get("text")).map(Object::toString).orElse("");
        long chatId = chat == null ? 0L : ((Number) chat.get("id")).longValue();
        long userId = from == null ? 0L : ((Number) from.get("id")).longValue();
        String username = from == null ? null : (String) from.get("username");

        SessionSource source = new SessionSource(
            Platform.TELEGRAM,
            String.valueOf(chatId),
            String.valueOf(userId),
            username == null ? "" : username,
            username == null ? "" : username
        );
        routingService.dispatchInbound(new MessageEvent(
            null,
            source,
            MessageType.TEXT,
            text,
            List.of(),
            Map.of(),
            Instant.now()
        ));
        return "OK";
    }
}
