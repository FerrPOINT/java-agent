package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.config.SharedObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B3.1: Manages DM topic threads — ensures a forum topic exists for a chat
 * and caches chat→topic mappings in memory.
 *
 * <p>P1 Enhancement: Now supports:
 * <ul>
 *   <li>Persistence of thread IDs to bot config JSON file (~/.java-agent/bot-config.json)</li>
 *   <li>Rename via Telegram API (editForumTopic)</li>
 *   <li>Reply anchor retry logic (retry with parent message if reply anchor fails)</li>
 *   <li>direct_messages_topic_id support</li>
 *   <li>Seed messages for topic visibility</li>
 *   <li>Loading persisted thread IDs from config on startup</li>
 * </ul>
 *
 * <p>Configured via {@code bot.group.dm-topics} — a list of {chatId, topicName, threadId?, iconColor?, iconCustomEmojiId?} entries.
 */
@Component
@Slf4j
public class DmTopicManager {

    private final BotProperties properties;
    private final TelegramClient telegramClient;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> topicCache = new ConcurrentHashMap<>();
    private final Path configFile;

    @org.springframework.beans.factory.annotation.Autowired
    public DmTopicManager(BotProperties properties, TelegramClient telegramClient) {
        this(properties, telegramClient,
            Path.of(System.getProperty("user.home"), ".java-agent", "bot-config.json"),
            SharedObjectMapper.pretty());
    }

    public DmTopicManager(BotProperties properties, TelegramClient telegramClient, Path configFile, ObjectMapper objectMapper) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.configFile = configFile;
        this.objectMapper = objectMapper;
    }

    /**
     * Ensure a topic exists for the given chat. If a cached thread ID exists,
     * return it. If the config has a persisted thread_id, load it. Otherwise,
     * create a forum topic via the Telegram API, cache it, and persist it.
     *
     * @param chatId    the chat ID
     * @param topicName the desired topic name
     * @return the thread/topic ID, or empty if creation failed
     */
    public Optional<Long> ensureTopic(long chatId, String topicName) {
        return ensureTopic(chatId, topicName, false);
    }

    /**
     * Ensure a topic exists, optionally forcing creation of a new topic.
     *
     * @param forceCreate if true, create a new topic even if one is cached
     * @return the thread/topic ID, or empty if creation failed
     */
    public Optional<Long> ensureTopic(long chatId, String topicName, boolean forceCreate) {
        if (topicName == null || topicName.isBlank()) {
            return Optional.empty();
        }
        String cacheKey = chatId + ":" + topicName;
        if (!forceCreate) {
            Long cached = topicCache.get(cacheKey);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        // Check if thread_id is already persisted in config
        if (!forceCreate) {
            Optional<Long> persisted = findPersistedThreadId(chatId, topicName);
            if (persisted.isPresent()) {
                topicCache.put(cacheKey, persisted.get());
                log.info("DM topic loaded from config: {} -> thread_id={}", cacheKey, persisted.get());
                return persisted;
            }
        }
        // Try to create a forum topic via Telegram API
        BotProperties.DmTopic topicConfig = findTopicConfig(chatId, topicName);
        Optional<Long> threadId = createForumTopic(chatId, topicName, topicConfig);
        threadId.ifPresent(id -> {
            topicCache.put(cacheKey, id);
            persistDmTopicThreadId(chatId, topicName, id, forceCreate);
            // Send a seed message so the topic is visible in Telegram's client.
            // Empty topics are hidden by the client UI until they contain a message.
            sendSeedMessage(chatId, id, topicName);
        });
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
     * Rename a forum topic in a chat.
     *
     * @param chatId   the chat ID
     * @param threadId the topic thread ID
     * @param newName  the new topic name
     * @return true if the rename succeeded
     */
    public boolean renameDmTopic(long chatId, long threadId, String newName) {
        if (newName == null || newName.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("message_thread_id", threadId);
            params.put("name", newName);
            var response = telegramClient.callApi("editForumTopic", params);
            boolean ok = response.isPresent() && response.get().isSuccess();
            if (ok) {
                log.info("Renamed DM topic in chat {} thread_id={} -> '{}'", chatId, threadId, newName);
            }
            return ok;
        } catch (Exception e) {
            log.warn("Failed to rename DM topic in chat {} thread_id={}: {}", chatId, threadId, e.getMessage());
            return false;
        }
    }

    /**
     * Send a message to a DM topic with reply anchor retry logic.
     *
     * <p>If the send fails because the reply anchor (reply_to_message_id) is
     * stale or deleted, retry without the reply anchor and topic routing.
     *
     * @param chatId            target chat ID
     * @param text              message text
     * @param parseMode         parse mode (MarkdownV2, HTML, or null)
     * @param replyToMessageId  optional reply-to message ID
     * @param threadId          optional thread/topic ID
     * @param directMessagesTopicId optional direct_messages_topic_id
     * @return the sent message ID, or empty on failure
     */
    public Optional<Long> sendWithDmTopicReplyAnchorRetry(
        long chatId, String text, String parseMode,
        Long replyToMessageId, Long threadId, Long directMessagesTopicId
    ) {
        // First attempt: send with reply anchor and topic routing
        Optional<Long> result = sendMessageWithTopic(chatId, text, parseMode, replyToMessageId, threadId, directMessagesTopicId);

        if (result.isEmpty() && replyToMessageId != null) {
            // Retry without the reply anchor — the reply target may have been deleted
            log.info("Reply anchor failed for chat {}, retrying without reply_to_message_id", chatId);
            result = sendMessageWithTopic(chatId, text, parseMode, null, threadId, directMessagesTopicId);
        }

        if (result.isEmpty() && threadId != null) {
            // Retry without thread routing — the topic may have been deleted/closed
            log.info("Thread routing failed for chat {} thread {}, retrying without thread", chatId, threadId);
            result = sendMessageWithTopic(chatId, text, parseMode, null, null, null);
        }

        return result;
    }

    /** Send a message with optional topic and reply-to routing. */
    private Optional<Long> sendMessageWithTopic(
        long chatId, String text, String parseMode,
        Long replyToMessageId, Long threadId, Long directMessagesTopicId
    ) {
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("text", text);
            if (parseMode != null && !parseMode.isBlank()) params.put("parse_mode", parseMode);
            if (replyToMessageId != null) params.put("reply_to_message_id", replyToMessageId);
            if (threadId != null) params.put("message_thread_id", threadId);
            if (directMessagesTopicId != null) params.put("direct_messages_topic_id", directMessagesTopicId);

            return telegramClient.callApi("sendMessage", params)
                .flatMap(r -> Optional.ofNullable(r.resultMessageIdAsLong()));
        } catch (Exception e) {
            log.debug("sendMessageWithTopic failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Send a seed message to make the topic visible in Telegram's client. */
    private void sendSeedMessage(long chatId, long threadId, String topicName) {
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("message_thread_id", threadId);
            params.put("text", "\uD83D\uDCCC " + topicName); // 📌 topicName
            telegramClient.callApi("sendMessage", params);
        } catch (Exception e) {
            log.debug("Could not send seed message to topic '{}': {}", topicName, e.getMessage());
        }
    }

    /**
     * Initialize topics from config — called on startup to pre-create configured DM topics.
     * Loads persisted thread IDs first, then creates topics that don't have one.
     */
    public void initializeConfiguredTopics() {
        for (BotProperties.DmTopic dmTopic : properties.getGroup().getDmTopics()) {
            try {
                long chatId = Long.parseLong(dmTopic.getChatId());
                // If thread_id is already persisted in config, just load into cache
                if (dmTopic.getThreadId() != null) {
                    String cacheKey = chatId + ":" + dmTopic.getTopicName();
                    topicCache.put(cacheKey, dmTopic.getThreadId());
                    log.info("DM topic loaded from config: {} -> thread_id={}", cacheKey, dmTopic.getThreadId());
                    continue;
                }
                // No persisted thread_id — create the topic via API
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

    /** Find the DmTopic config for a chat+topicName, if it exists. */
    private BotProperties.DmTopic findTopicConfig(long chatId, String topicName) {
        for (BotProperties.DmTopic dmTopic : properties.getGroup().getDmTopics()) {
            if (String.valueOf(chatId).equals(dmTopic.getChatId())
                && topicName.equals(dmTopic.getTopicName())) {
                return dmTopic;
            }
        }
        return null;
    }

    /** Find a persisted thread ID from the config for a chat+topicName. */
    private Optional<Long> findPersistedThreadId(long chatId, String topicName) {
        for (BotProperties.DmTopic dmTopic : properties.getGroup().getDmTopics()) {
            if (String.valueOf(chatId).equals(dmTopic.getChatId())
                && topicName.equals(dmTopic.getTopicName())
                && dmTopic.getThreadId() != null) {
                return Optional.of(dmTopic.getThreadId());
            }
        }
        return Optional.empty();
    }

    /** Create a forum topic via the Telegram API. */
    @SuppressWarnings("unchecked")
    private Optional<Long> createForumTopic(long chatId, String topicName, BotProperties.DmTopic topicConfig) {
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("chat_id", chatId);
            params.put("name", topicName);
            if (topicConfig != null && topicConfig.getIconColor() != null) {
                params.put("icon_color", topicConfig.getIconColor());
            }
            if (topicConfig != null && topicConfig.getIconCustomEmojiId() != null && !topicConfig.getIconCustomEmojiId().isBlank()) {
                params.put("icon_custom_emoji_id", topicConfig.getIconCustomEmojiId());
            }
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
     * Persist a newly created thread_id back into the bot config JSON file
     * so it survives restarts.
     */
    void persistDmTopicThreadId(long chatId, String topicName, long threadId, boolean replaceExisting) {
        try {
            // Load existing config
            ObjectNode config;
            if (Files.exists(configFile)) {
                config = (ObjectNode) objectMapper.readTree(Files.readAllBytes(configFile));
            } else {
                Files.createDirectories(configFile.getParent());
                config = objectMapper.createObjectNode();
            }

            // Navigate to dm_topics array
            ObjectNode bot = config.has("bot") ? (ObjectNode) config.get("bot") : config.putObject("bot");
            ObjectNode group = bot.has("group") ? (ObjectNode) bot.get("group") : bot.putObject("group");
            ArrayNode dmTopics = group.has("dm-topics") ? (ArrayNode) group.get("dm-topics") : group.putArray("dm-topics");

            boolean changed = false;
            boolean foundChat = false;
            boolean foundTopic = false;

            for (int i = 0; i < dmTopics.size(); i++) {
                ObjectNode entry = (ObjectNode) dmTopics.get(i);
                String entryChatId = entry.has("chatId") ? entry.get("chatId").asText() : "";
                if (!entryChatId.equals(String.valueOf(chatId))) continue;
                foundChat = true;
                String entryTopicName = entry.has("topicName") ? entry.get("topicName").asText() : "";
                if (!entryTopicName.equals(topicName)) continue;
                foundTopic = true;
                long existingThreadId = entry.has("threadId") ? entry.get("threadId").asLong() : 0;
                if (replaceExisting || existingThreadId == 0) {
                    if (existingThreadId != threadId) {
                        entry.put("threadId", threadId);
                        changed = true;
                    }
                }
                break;
            }

            if (!foundTopic) {
                if (!foundChat) {
                    ObjectNode newEntry = dmTopics.addObject();
                    newEntry.put("chatId", String.valueOf(chatId));
                    ArrayNode topicsArr = newEntry.putArray("topics");
                    ObjectNode topicEntry = topicsArr.addObject();
                    topicEntry.put("name", topicName);
                    topicEntry.put("threadId", threadId);
                } else {
                    // Add to existing chat entry
                    for (int i = 0; i < dmTopics.size(); i++) {
                        ObjectNode entry = (ObjectNode) dmTopics.get(i);
                        if (String.valueOf(chatId).equals(entry.has("chatId") ? entry.get("chatId").asText() : "")) {
                            ArrayNode topics = entry.has("topics") ? (ArrayNode) entry.get("topics") : entry.putArray("topics");
                            ObjectNode topicEntry = topics.addObject();
                            topicEntry.put("name", topicName);
                            topicEntry.put("threadId", threadId);
                            break;
                        }
                    }
                }
                changed = true;
            }

            if (changed) {
                Files.writeString(configFile, objectMapper.writeValueAsString(config));
                log.info("Persisted thread_id={} for topic '{}' in {}", threadId, topicName, configFile);
            }
        } catch (IOException e) {
            log.warn("Failed to persist thread_id to config: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to persist thread_id to config: {}", e.getMessage(), e);
        }
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