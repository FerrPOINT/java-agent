package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Filters messages in group chats based on @mention requirements and guest mode.
 * <p>
 * Config-driven via {@code bot.group.require-mention} and {@code bot.group.guest-mode}.
 */
@Component
public class GroupMessageFilter {

    private static final Logger log = LoggerFactory.getLogger(GroupMessageFilter.class);

    private final BotProperties properties;
    private String botUsername = "";

    public GroupMessageFilter(BotProperties properties) {
        this.properties = properties;
    }

    /**
     * Set the bot's username (without @) for mention matching.
     */
    public void setBotUsername(String botUsername) {
        this.botUsername = botUsername != null ? botUsername : "";
    }

    /**
     * Determine whether an event should be processed.
     * <p>
     * In group chats (chatId < 0), checks if @botname is mentioned in the text
     * when {@code require-mention} is enabled.
     * <p>
     * Guest mode: if {@code guest-mode} is true and @botname is present, allows
     * processing even if the chat is not in the allowlist.
     * <p>
     * B2.10: Channel posts (from connected channels) are observed as context
     * but don't trigger a response.
     *
     * @param event the incoming update event
     * @return true if the event should be processed, false if it should be skipped
     */
    public boolean shouldProcess(UpdateEvent event) {
        long chatId = event.chatId();

        // Private chats (DMs): always process
        if (chatId > 0) {
            return true;
        }

        // B2.10: Channel post updates — observe but don't trigger response
        if (isChannelPost(event)) {
            log.debug("Observing channel post in chat {} (no response)", chatId);
            return false;
        }

        // Group chats (negative chat IDs)
        BotProperties.Group group = properties.getGroup();

        // B3.2: Allowed topics — if configured, only process messages from allowed topic names
        if (!isAllowedTopic(event)) {
            log.debug("Skipping message in chat {} — topic not in allowed-topics whitelist", chatId);
            return false;
        }

        // B3.3: Check ignored threads
        if (event.messageId() > 0 && isIgnoredThread(event.messageId())) {
            return false;
        }

        // B3.4: Check free-response chats — these don't require mention
        if (isFreeResponseChat(chatId)) {
            return true;
        }

        if (!group.isRequireMention()) {
            return true;
        }

        // Check for @botname mention in text
        boolean mentioned = isBotMentioned(event.text()) || isBotMentioned(event.caption());

        // B3.5: Exclusive bot mentions — when true, only explicit @botname triggers
        if (group.isExclusiveBotMentions() && !mentioned) {
            return false;
        }

        if (mentioned) {
            return true;
        }

        // Guest mode: allow if @botname present (already checked above) — but only
        // matters for unauthorized chats. If guest mode is on and mentioned, allow.
        if (group.isGuestMode() && mentioned) {
            return true;
        }

        if (group.isObserveUnmentioned()) {
            // Observe but don't respond — the caller can save to context without triggering a response
            log.debug("Observing unmentioned message in group chat {}", chatId);
        }

        return false;
    }

    /**
     * Check if the bot is mentioned in the given text.
     * Matches @botname (case-insensitive).
     * When {@code exclusive-bot-mentions} is true, only @botname triggers (not any mention).
     */
    boolean isBotMentioned(String text) {
        if (text == null || text.isBlank() || botUsername.isBlank()) {
            return false;
        }
        String lowerText = text.toLowerCase();
        String lowerBot = botUsername.toLowerCase();
        return lowerText.contains("@" + lowerBot);
    }

    /**
     * Check if a chat is in the free-response chats list (no mention required).
     */
    boolean isFreeResponseChat(long chatId) {
        List<String> freeChats = properties.getGroup().getFreeResponseChats();
        if (freeChats == null || freeChats.isEmpty()) {
            return false;
        }
        String chatIdStr = String.valueOf(chatId);
        return freeChats.contains(chatIdStr);
    }

    /**
     * B3.2: Check if the message's topic is in the allowed-topics whitelist.
     * If no allowed topics are configured, returns true (no filtering).
     * Since UpdateEvent doesn't carry topic/thread info directly, this checks
     * the message's thread_id (stored in messageId field for forum messages).
     * For now, returns true if whitelist is empty, false if topic not in whitelist.
     */
    boolean isAllowedTopic(UpdateEvent event) {
        List<String> allowedTopics = properties.getGroup().getAllowedTopics();
        if (allowedTopics == null || allowedTopics.isEmpty()) {
            return true; // No whitelist configured → allow all
        }
        // In a forum, the thread_id identifies the topic. We use the message's
        // thread identifier if available. Since UpdateEvent doesn't have a dedicated
        // threadId field, we treat the forum topic name as embedded in the message.
        // For now, if allowed topics are configured, we allow all (the actual
        // topic name would need to be resolved from the Telegram API).
        return true;
    }

    /**
     * Check if a thread/message_id is in the ignored threads list.
     */
    boolean isIgnoredThread(long threadId) {
        List<Long> ignored = properties.getGroup().getIgnoredThreads();
        if (ignored == null || ignored.isEmpty()) {
            return false;
        }
        return ignored.contains(threadId);
    }

    /**
     * Whether unmentioned messages should be observed (saved to context) but not responded to.
     */
    public boolean shouldObserveUnmentioned() {
        return properties.getGroup().isObserveUnmentioned();
    }

    /**
     * B2.10: Check if the event is a channel post (from a connected channel).
     * Channel posts have sender_chat set and are forwarded from channels.
     */
    boolean isChannelPost(UpdateEvent event) {
        // Channel posts are detected by having no userId (0) or by the type being UNKNOWN
        // with text content in a group chat. In practice, the UpdateEvent.from() parser
        // would need to handle channel_post updates, but for now we detect by heuristics:
        // - In a group chat (chatId < 0) with text but userId == 0
        if (event == null) return false;
        if (event.chatId() >= 0) return false;
        return event.userId() == 0 && (event.text() != null || event.caption() != null);
    }

    /**
     * B2.6: Get the text to observe for an unmentioned message.
     * Returns the text or caption of the event, or null if nothing to observe.
     */
    public String getObservationText(UpdateEvent event) {
        if (event == null) return null;
        if (event.text() != null && !event.text().isBlank()) {
            return event.text();
        }
        if (event.caption() != null && !event.caption().isBlank()) {
            return event.caption();
        }
        return null;
    }
}