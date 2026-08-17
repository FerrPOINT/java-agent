package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextBatchDebouncerTest {

    private TextBatchDebouncer debouncer;
    private BotProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        debouncer = new TextBatchDebouncer(properties);
    }

    private UpdateEvent textEvent(long id, long chatId, String text) {
        return new UpdateEvent(id, UpdateEvent.Type.TEXT, chatId, 200L,
            "user", text, null, null, null, null, null, null,
            false, null, null, 100, null, 0);
    }

    @Test
    void shortMessagesJoinedWithSpace() {
        long chatId = 100L;
        debouncer.offer(textEvent(1, chatId, "what is"));
        debouncer.offer(textEvent(2, chatId, "an API"));

        String pending = debouncer.getPendingText(chatId);
        assertThat(pending).isEqualTo("what is an API");
    }

    @Test
    void longMessagesJoinedWithNewline() {
        long chatId = 200L;
        String long1 = "This is a very long paragraph that exceeds the 320 character limit. ".repeat(10);
        String long2 = "Another long paragraph that also exceeds the limit. ".repeat(10);

        debouncer.offer(textEvent(1, chatId, long1));
        debouncer.offer(textEvent(2, chatId, long2));

        String pending = debouncer.getPendingText(chatId);
        assertThat(pending).contains("\n");
        assertThat(pending).doesNotContain(long1 + " " + long2);
    }

    @Test
    void mixedShortAndLongJoinedWithNewline() {
        long chatId = 300L;
        String long1 = "This is a very long paragraph that exceeds the 320 character limit. ".repeat(10);

        debouncer.offer(textEvent(1, chatId, "short text"));
        debouncer.offer(textEvent(2, chatId, long1));

        String pending = debouncer.getPendingText(chatId);
        // Conservative: if any message is long, use newline
        assertThat(pending).contains("\n");
    }

    // ─── P34: Post-Debounce Re-Validation tests ─────────────────────

    @Test
    void shouldDispatch_false_dropsBatch() {
        long chatId = 400L;
        java.util.List<UpdateEvent> dispatched = new java.util.ArrayList<>();
        debouncer.onDispatch(dispatched::add);

        // Set predicate that denies dispatch for chatId 400
        debouncer.setShouldDispatch(id -> id != 400L);

        debouncer.offer(textEvent(1, chatId, "hello world"));
        debouncer.flushAll();

        assertThat(dispatched).isEmpty();
        assertThat(debouncer.hasPending(chatId)).isFalse();
    }

    @Test
    void shouldDispatch_true_dispatchesNormally() {
        long chatId = 500L;
        java.util.List<UpdateEvent> dispatched = new java.util.ArrayList<>();
        debouncer.onDispatch(dispatched::add);

        // Set predicate that allows dispatch for chatId 500
        debouncer.setShouldDispatch(id -> id == 500L);

        debouncer.offer(textEvent(1, chatId, "hello world"));
        debouncer.flushAll();

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).text()).isEqualTo("hello world");
    }

    @Test
    void shouldDispatch_null_dispatchesAll() {
        long chatId = 600L;
        java.util.List<UpdateEvent> dispatched = new java.util.ArrayList<>();
        debouncer.onDispatch(dispatched::add);

        // No predicate set — dispatch should proceed normally
        debouncer.offer(textEvent(1, chatId, "test message"));
        debouncer.flushAll();

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).text()).isEqualTo("test message");
    }
}