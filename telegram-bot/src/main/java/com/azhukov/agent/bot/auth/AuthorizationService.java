package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);
    public static final String WILDCARD = "*";

    private final BotProperties properties;

    public AuthorizationService(BotProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthorized(UpdateEvent event) {
        return isAuthorized(event.userId(), event.username(), event.chatId());
    }

    public boolean isAuthorized(long userId, String username, long chatId) {
        var auth = properties.getAuth();

        // 1. allow-by-default
        if (auth.isAllowByDefault()) return true;

        String userIdStr = String.valueOf(userId);
        String chatIdStr = String.valueOf(chatId);

        // 2. wildcard in any list
        if (containsWildcard(auth.getAllowedUserIds())) return true;
        if (containsWildcard(auth.getAllowedUsernames())) return true;
        if (containsWildcard(auth.getAllowedChatIds())) return true;

        // 3. user-id match
        if (auth.getAllowedUserIds().contains(userIdStr)) return true;

        // 4. username match
        if (username != null && !username.isBlank() && auth.getAllowedUsernames().contains(username)) return true;

        // 5. chat-id match (group allowlist)
        if (auth.getAllowedChatIds().contains(chatIdStr)) return true;

        // 6. deny (fail-closed)
        log.debug("Authorization denied for userId={} username={} chatId={}", userId, username, chatId);
        return false;
    }

    private boolean containsWildcard(List<String> list) {
        return list != null && list.contains(WILDCARD);
    }
}
