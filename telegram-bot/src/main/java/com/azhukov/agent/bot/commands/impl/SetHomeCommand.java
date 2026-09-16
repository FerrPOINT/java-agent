package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A2.10: /set_home — set current chat as the home channel.
 *
 * <p>WP-2 fix: the home channel is now persisted in the backend gateway
 * home-channel directory ({@code PUT /api/gateway/home-channel}) so bare
 * {@code deliver="telegram"} targets and send_message bare-platform targets
 * survive bot restarts. The in-memory {@code BotProperties.homeChatId}
 * mirror is still updated for legacy readers ({@code /profile}, debug).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SetHomeCommand implements CommandHandler {

    private final BotProperties properties;

    @Qualifier("backendRestClient")
    private final RestClient backendRestClient;

    @Override
    public String name() {
        return "set_home";
    }

    @Override
    public String description() {
        return "Set current chat as home channel";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        long chatId = event.chatId();
        properties.setHomeChatId(String.valueOf(chatId));

        String threadNote = "";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("platform", "telegram");
            body.put("chat_id", String.valueOf(chatId));
            body.put("name", "Home");
            if (event.userId() != 0) {
                body.put("user_id", String.valueOf(event.userId()));
            }
            body.put("updated_by", event.username() == null ? "set_home" : event.username());
            if (event.messageThreadId() > 0) {
                body.put("thread_id", String.valueOf(event.messageThreadId()));
                threadNote = " (topic " + event.messageThreadId() + ")";
            }
            backendRestClient.put()
                .uri("/api/gateway/home-channel")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Persisting home channel to backend failed (in-memory only): {}", e.getMessage());
            return "Home channel set to chat " + chatId + " for this process only — backend persist failed: "
                + e.getMessage();
        }
        return "Home channel set to chat " + chatId + threadNote + " and persisted.";
    }
}
