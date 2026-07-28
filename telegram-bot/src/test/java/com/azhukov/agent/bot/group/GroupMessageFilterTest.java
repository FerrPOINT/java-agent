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

    private UpdateEvent makeEvent(long chatId, String text, long messageId) {
        return new UpdateEvent(1L, UpdateEvent.Type.TEXT, chatId, 200L,
            "jdoe", text, null, null, null,
            null, null, null, false, null, null, messageId, null);
    }
}