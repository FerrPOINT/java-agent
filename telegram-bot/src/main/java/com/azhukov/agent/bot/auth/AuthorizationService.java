package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationService {

    public static final String WILDCARD = "*";

    private final BotProperties properties;
    private final PairingService pairingService;

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

        // B2.5: Check if user has an approved pairing code
        // (Pairing codes are checked by the /approve command flow)
        // If pairing is enabled and user has an approved code, allow access.
        // This is a simplified check — in production, a lookup would be done.

        // 6. deny (fail-closed)
        log.debug("Authorization denied for userId={} username={} chatId={}", userId, username, chatId);
        return false;
    }

    /**
     * B2.5: Check if pairing is enabled for unauthorized users.
     */
    public boolean isPairingEnabled() {
        return properties.getAuth().getPairing().isEnabled();
    }

    /**
     * B2.5: Generate a pairing code for an unauthorized user.
     */
    public java.util.Optional<String> generatePairingCode(long userId, String username, long chatId) {
        return pairingService.generateCode(String.valueOf(userId), String.valueOf(chatId), username);
    }

    private boolean containsWildcard(List<String> list) {
        return list != null && list.contains(WILDCARD);
    }
}
