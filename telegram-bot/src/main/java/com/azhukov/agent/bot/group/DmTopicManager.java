package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B3.1: Manages DM topic threads — ensures a forum topic exists for a chat
 * and caches chat→topic mappings in memory.
 * <p>
 * Configured via {@code bot.group.dm-topics} — a list of {chatId, topicName} entries.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmTopicManager {

    private final BotProperties properties;
    private final TelegramClient telegramClient;
    private final Map<String, Long> topicCache = new ConcurrentHashMap<>();

    /**
     * Ensure a topic exists for the given chat. If a cached thread ID exists,
     * return it. Otherwise, create a forum topic via the Telegram API and cache it.
     *
     * @param chatId    the chat ID (negative for groups)
     * @param topicName the desired topic name
     * @return the thread/topic ID, or empty if creation failed
     */
    public Optional<Long> ensureTopic(long chatId, String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return Optional.empty();
        }
        String cacheKey = chatId + ":" + topicName;
        Long cached = topicCache.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        // Try to create a forum topic via Telegram API
        Optional<Long> threadId = createForumTopic(chatId, topicName);
        threadId.ifPresent(id -> topicCache.put(cacheKey, id));
        return threadId;
    }

    /**
     * Get the cached thread ID for a chat+topicName, if it exists.
     */
    public Optional<Long> getCachedTopicId(long chatId, String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(topicCache.get(chatId + ":" + topicName));
    }

    /**
     * Initialize topics from config — called on startup to pre-create configured DM topics.
     */
    public void initializeConfiguredTopics() {
        for (BotProperties.DmTopic dmTopic : properties.getGroup().getDmTopics()) {
            try {
                long chatId = Long.parseLong(dmTopic.getChatId());
                Optional<Long> threadId = ensureTopic(chatId, dmTopic.getTopicName());
                threadId.ifPresentOrElse(
                    id -> log.info("DM topic '{}' ensured in chat {} → thread {}", dmTopic.getTopicName(), chatId, id),
                    () -> log.warn("Failed to ensure DM topic '{}' in chat {}", dmTopic.getTopicName(), chatId)
                );
            } catch (NumberFormatException e) {
                log.warn("Invalid chatId in dm-topics config: {}", dmTopic.getChatId());
            }
        }
    }

    /**
     * Create a forum topic via the Telegram API.
     * Uses the createForumTopic method (not yet in TelegramClient, so we use callApi directly).
     */
    @SuppressWarnings("unchecked")
    private Optional<Long> createForumTopic(long chatId, String topicName) {
        try {
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("name", topicName);
            var response = telegramClient.callApi("createForumTopic", params);
            if (response.isPresent()) {
                Object result = response.get().result();
                if (result instanceof Map<?, ?> map) {
                    Object msgThreadId = map.get("message_thread_id");
                    if (msgThreadId instanceof Number num) {
                        return Optional.of(num.longValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to create forum topic '{}' in chat {}: {}", topicName, chatId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Clear the topic cache (for testing or config reload).
     */
    public void clearCache() {
        topicCache.clear();
    }

    /**
     * Get the cache size (for testing).
     */
    public int cacheSize() {
        return topicCache.size();
    }
}