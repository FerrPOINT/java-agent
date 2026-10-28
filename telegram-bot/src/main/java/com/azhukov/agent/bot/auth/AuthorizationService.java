package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Restricts Telegram access to explicitly configured user IDs. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationService {
    private final BotProperties properties;

    public boolean isAuthorized(UpdateEvent event) {
        return isAuthorized(event.userId(), event.username(), event.chatId());
    }

    public boolean isAuthorized(long userId, String username, long chatId) {
        if (properties.getAuth().getAllowedUserIds().contains(String.valueOf(userId))) return true;
        log.debug("Authorization denied for userId={} username={} chatId={}", userId, username, chatId);
        return false;
    }
}
