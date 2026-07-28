package com.azhukov.agent.bot.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class BotHealthController {

    @GetMapping("/bot/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "telegram-bot");
    }
}