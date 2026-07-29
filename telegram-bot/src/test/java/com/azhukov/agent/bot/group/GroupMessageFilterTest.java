package com.azhukov.agent.bot.group;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupMessageFilterTest {

    private BotProperties properties;
    private GroupMessageFilter filter;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        filter = new GroupMessageFilter(properties);
        filter.setBotUsername("mybot");
    }

    @Test
    void shouldProcess_privateChat_alwaysTrue() {
        UpdateEvent event = makeEvent(123L, "Hello", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void shouldProcess_groupChat_requireMention_true_withoutMention_returnsFalse() {
        properties.getGroup().setRequireMention(true);
        UpdateEvent event = makeEvent(-100123L, "Hello world", 0L);
        assertThat(filter.shouldProcess(event)).isFalse();
    }

    @Test
    void shouldProcess_groupChat_requireMention_true_withMention_returnsTrue() {
        properties.getGroup().setRequireMention(true);
        UpdateEvent event = makeEvent(-100123L, "Hello @mybot", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void shouldProcess_groupChat_guestMode_mentionAllows() {
        properties.getGroup().setRequireMention(true);
        properties.getGroup().setGuestMode(true);
        // Even though not authorized, @mention should allow processing
        UpdateEvent event = makeEvent(-100999L, "Help @mybot please", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void shouldProcess_groupChat_freeResponseChat_allowsWithoutMention() {
        properties.getGroup().setRequireMention(true);
        properties.getGroup().getFreeResponseChats().add("-100123");
        UpdateEvent event = makeEvent(-100123L, "Hello without mention", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void isBotMentioned_correctlyDetectsMention() {
        assertThat(filter.isBotMentioned("Hello @mybot")).isTrue();
        assertThat(filter.isBotMentioned("Hello @MyBot")).isTrue(); // case-insensitive
        assertThat(filter.isBotMentioned("Hello world")).isFalse();
        assertThat(filter.isBotMentioned(null)).isFalse();
        assertThat(filter.isBotMentioned("")).isFalse();
    }

    // ─── B3.2: isAllowedTopic ─────────────────────────────────────

    @Test
    void isAllowedTopic_noWhitelistConfigured_returnsTrue() {
        // No allowed-topics configured → should allow all
        assertThat(properties.getGroup().getAllowedTopics()).isEmpty();
        UpdateEvent event = makeEvent(-100123L, "Hello", 0L);
        assertThat(filter.isAllowedTopic(event)).isTrue();
    }

    @Test
    void isAllowedTopic_whitelistConfiguredButUnresolvable_stillAllowsAll() {
        // With allowed-topics configured, the current implementation returns true
        // (topic name resolution from Telegram API is not yet implemented)
        properties.getGroup().getAllowedTopics().add("general");
        UpdateEvent event = makeEvent(-100123L, "Hello", 0L);
        assertThat(filter.isAllowedTopic(event)).isTrue();
    }

    // ─── B3.3: isIgnoredThread ─────────────────────────────────────

    @Test
    void isIgnoredThread_notInIgnoredList_returnsFalse() {
        assertThat(filter.isIgnoredThread(500L)).isFalse();
    }

    @Test
    void isIgnoredThread_inIgnoredList_returnsTrue() {
        properties.getGroup().getIgnoredThreads().add(500L);
        assertThat(filter.isIgnoredThread(500L)).isTrue();
    }

    @Test
    void shouldProcess_ignoredThread_returnsFalse() {
        // Group chat with require-mention off, but thread is in ignored list
        properties.getGroup().getIgnoredThreads().add(42L);
        UpdateEvent event = makeEvent(-100123L, "Hello", 42L);
        assertThat(filter.shouldProcess(event)).isFalse();
    }

    @Test
    void shouldProcess_ignoredThread_notIgnoredId_returnsTrue() {
        // messageId=0 should not trigger ignored-thread check (guard: messageId > 0)
        properties.getGroup().getIgnoredThreads().add(42L);
        UpdateEvent event = makeEvent(-100123L, "Hello", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    // ─── B3.4: Free-response chat allows without mention ──────────

    @Test
    void shouldProcess_freeResponseChat_allowsWithoutMention_explicit() {
        // B3.4 explicit test: free-response chat bypasses mention requirement
        properties.getGroup().setRequireMention(true);
        properties.getGroup().getFreeResponseChats().add("-100555");
        UpdateEvent event = makeEvent(-100555L, "Hello without mention", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void shouldProcess_nonFreeResponseChat_withRequireMention_noMention_returnsFalse() {
        // A non-free-response chat with requireMention=true and no mention → false
        properties.getGroup().setRequireMention(true);
        // -100123 is NOT in free-response chats
        UpdateEvent event = makeEvent(-100123L, "Hello no mention", 0L);
        assertThat(filter.shouldProcess(event)).isFalse();
    }

    @Test
    void shouldProcess_freeResponseChat_doesNotRequireMention() {
        // Even with requireMention=false, free-response chat still works
        properties.getGroup().setRequireMention(false);
        properties.getGroup().getFreeResponseChats().add("-100555");
        UpdateEvent event = makeEvent(-100555L, "Hello", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    // ─── B3.5: Exclusive bot mentions ──────────────────────────────

    @Test
    void shouldProcess_exclusiveBotMentions_true_noMention_returnsFalse() {
        // B3.5: when exclusiveBotMentions=true and no mention, should not process
        properties.getGroup().setRequireMention(true);
        properties.getGroup().setExclusiveBotMentions(true);
        UpdateEvent event = makeEvent(-100123L, "Hello world", 0L);
        assertThat(filter.shouldProcess(event)).isFalse();
    }

    @Test
    void shouldProcess_exclusiveBotMentions_true_withMention_returnsTrue() {
        // B3.5: when exclusiveBotMentions=true and mention present, should process
        properties.getGroup().setRequireMention(true);
        properties.getGroup().setExclusiveBotMentions(true);
        UpdateEvent event = makeEvent(-100123L, "Hey @mybot help me", 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    @Test
    void shouldProcess_exclusiveBotMentions_false_noMention_requireMentionTrue_returnsFalse() {
        // When exclusiveBotMentions=false and requireMention=true, non-mention blocked
        properties.getGroup().setRequireMention(true);
        properties.getGroup().setExclusiveBotMentions(false);
        UpdateEvent event = makeEvent(-100123L, "Hello world", 0L);
        assertThat(filter.shouldProcess(event)).isFalse();
    }

    @Test
    void shouldProcess_exclusiveBotMentions_true_withCaptionMention_returnsTrue() {
        // B3.5: mention detected in caption should also pass
        properties.getGroup().setRequireMention(true);
        properties.getGroup().setExclusiveBotMentions(true);
        UpdateEvent event = new UpdateEvent(1L, UpdateEvent.Type.TEXT, -100123L, 200L,
            "jdoe", null, "Check this @mybot", null, null,
            null, null, null, false, null, null, 0L, null, 0L);
        assertThat(filter.shouldProcess(event)).isTrue();
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private UpdateEvent makeEvent(long chatId, String text, long messageId) {
        return new UpdateEvent(1L, UpdateEvent.Type.TEXT, chatId, 200L,
            "jdoe", text, null, null, null,
            null, null, null, false, null, null, messageId, null, 0L);
    }
}