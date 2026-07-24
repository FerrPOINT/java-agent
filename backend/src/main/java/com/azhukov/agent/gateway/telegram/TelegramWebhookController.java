package com.azhukov.agent.gateway.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/gateway/telegram")
@ConditionalOnProperty(name = "agent.gateway.telegram.webhook.enabled", havingValue = "true")
public class TelegramWebhookController {

    @PostMapping
    public String receive(@RequestBody Map<String, Object> update) {
        // TODO dispatch to TelegramAdapter -> AgentRuntime (Phase F5)
        return "OK";
    }
}
