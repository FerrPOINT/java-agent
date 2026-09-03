package com.azhukov.agent.bot.polling;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateEventTest {

    @Test
    void fromTextMessage() {
        Map<String, Object> update = baseUpdate(100L);
        update.put("message", messageWithText(123L, 456L, "jdoe", "Hello world"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.updateId()).isEqualTo(100L);
        assertThat(event.type()).isEqualTo(UpdateEvent.Type.TEXT);
        assertThat(event.chatId()).isEqualTo(123L);
        assertThat(event.userId()).isEqualTo(456L);
        assertThat(event.username()).isEqualTo("jdoe");
        assertThat(event.text()).isEqualTo("Hello world");
        assertThat(event.isCommand()).isFalse();
    }

    @Test
    void fromCommandMessage() {
        Map<String, Object> update = baseUpdate(101L);
        update.put("message", messageWithText(123L, 456L, "jdoe", "/new"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.COMMAND);
        assertThat(event.isCommand()).isTrue();
        assertThat(event.commandName()).isEqualTo("new");
        assertThat(event.commandArgs()).isEqualTo("");
    }

    @Test
    void fromCommandWithArgs() {
        Map<String, Object> update = baseUpdate(102L);
        update.put("message", messageWithText(123L, 456L, "jdoe", "/title My Task"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.COMMAND);
        assertThat(event.commandName()).isEqualTo("title");
        assertThat(event.commandArgs()).isEqualTo("My Task");
    }

    @Test
    void fromCommandWithBotMention() {
        Map<String, Object> update = baseUpdate(103L);
        update.put("message", messageWithText(123L, 456L, "jdoe", "/help@java_agent_bot"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.commandName()).isEqualTo("help");
    }

    @Test
    void fromCommandLowercasesWithRootLocaleLikeHermes() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Map<String, Object> update = baseUpdate(109L);
            update.put("message", messageWithText(123L, 456L, "jdoe", "/INIT"));

            UpdateEvent event = UpdateEvent.from(update);

            assertThat(event.commandName()).isEqualTo("init");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void fromCallbackQuery() {
        Map<String, Object> update = baseUpdate(104L);
        Map<String, Object> cq = new LinkedHashMap<>();
        cq.put("id", "cq-1");
        cq.put("data", "model:kimi-k2");
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("id", 456);
        from.put("username", "jdoe");
        cq.put("from", from);
        Map<String, Object> msg = new LinkedHashMap<>();
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", 123);
        msg.put("chat", chat);
        cq.put("message", msg);
        update.put("callback_query", cq);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.CALLBACK_QUERY);
        assertThat(event.callbackQueryId()).isEqualTo("cq-1");
        assertThat(event.callbackData()).isEqualTo("model:kimi-k2");
        assertThat(event.chatId()).isEqualTo(123L);
    }

    @Test
    void fromPhotoMessage() {
        Map<String, Object> update = baseUpdate(105L);
        Map<String, Object> msg = messageBase(123L, 456L, "jdoe");
        msg.put("photo", List.of(
            Map.of("file_id", "small", "file_size", 100),
            Map.of("file_id", "large", "file_size", 5000)
        ));
        msg.put("caption", "Check this");
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.PHOTO);
        assertThat(event.fileId()).isEqualTo("large");
        assertThat(event.caption()).isEqualTo("Check this");
    }

    @Test
    void fromDocumentMessage() {
        Map<String, Object> update = baseUpdate(106L);
        Map<String, Object> msg = messageBase(123L, 456L, "jdoe");
        msg.put("document", Map.of("file_id", "doc-1", "file_name", "report.pdf"));
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.DOCUMENT);
        assertThat(event.fileId()).isEqualTo("doc-1");
    }

    @Test
    void fromVoiceMessage() {
        Map<String, Object> update = baseUpdate(107L);
        Map<String, Object> msg = messageBase(123L, 456L, "jdoe");
        msg.put("voice", Map.of("file_id", "voice-1", "duration", 5));
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.VOICE);
        assertThat(event.fileId()).isEqualTo("voice-1");
    }

    @Test
    void fromUnknownUpdate() {
        Map<String, Object> update = baseUpdate(108L);
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.UNKNOWN);
    }

    // ─── Edited message ────────────────────────────────────────────

    @Test
    void fromEditedMessageIsEditedMessageType() {
        Map<String, Object> update = baseUpdate(200L);
        update.put("edited_message", messageWithText(123L, 456L, "jdoe", "Edited text"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.EDITED_MESSAGE);
        assertThat(event.text()).isEqualTo("Edited text");
        assertThat(event.chatId()).isEqualTo(123L);
        assertThat(event.userId()).isEqualTo(456L);
        assertThat(event.username()).isEqualTo("jdoe");
        assertThat(event.isCommand()).isFalse();
    }

    @Test
    void fromEditedCommandMessageIsStillEditedMessageType() {
        Map<String, Object> update = baseUpdate(201L);
        update.put("edited_message", messageWithText(123L, 456L, "jdoe", "/new"));
        UpdateEvent event = UpdateEvent.from(update);

        // Edited messages are never commands — type stays EDITED_MESSAGE
        assertThat(event.type()).isEqualTo(UpdateEvent.Type.EDITED_MESSAGE);
        assertThat(event.isCommand()).isFalse();
        assertThat(event.commandName()).isNull();
    }

    @Test
    void fromEditedPhotoMessageIsEditedMessageType() {
        Map<String, Object> update = baseUpdate(202L);
        Map<String, Object> msg = messageBase(123L, 456L, "jdoe");
        msg.put("photo", List.of(
            Map.of("file_id", "small", "file_size", 100),
            Map.of("file_id", "large", "file_size", 5000)
        ));
        msg.put("caption", "Edited caption");
        update.put("edited_message", msg);
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.type()).isEqualTo(UpdateEvent.Type.EDITED_MESSAGE);
    }

    // ─── Forwarded messages ────────────────────────────────────────

    @Test
    void fromForwardedMessageWithForwardFromPrefixesText() {
        Map<String, Object> update = baseUpdate(210L);
        Map<String, Object> msg = messageWithText(123L, 456L, "jdoe", "Hello from forward");
        Map<String, Object> forwardFrom = new LinkedHashMap<>();
        forwardFrom.put("id", 789);
        forwardFrom.put("username", "original_sender");
        msg.put("forward_from", forwardFrom);
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.forwardedFrom()).isEqualTo("original_sender");
        assertThat(event.text()).contains("[Forwarded from @original_sender]:");
        assertThat(event.text()).contains("Hello from forward");
    }

    @Test
    void fromForwardedMessageWithForwardOriginUserPrefixesText() {
        Map<String, Object> update = baseUpdate(211L);
        Map<String, Object> msg = messageWithText(123L, 456L, "jdoe", "Forwarded via origin");
        Map<String, Object> forwardOrigin = new LinkedHashMap<>();
        forwardOrigin.put("type", "user");
        Map<String, Object> senderUser = new LinkedHashMap<>();
        senderUser.put("id", 999);
        senderUser.put("username", "origin_user");
        forwardOrigin.put("sender_user", senderUser);
        msg.put("forward_origin", forwardOrigin);
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.forwardedFrom()).isEqualTo("origin_user");
        assertThat(event.text()).startsWith("[Forwarded from @origin_user]:");
    }

    @Test
    void fromForwardedMessageWithForwardOriginHiddenUserUsesName() {
        Map<String, Object> update = baseUpdate(212L);
        Map<String, Object> msg = messageWithText(123L, 456L, "jdoe", "Hidden forward");
        Map<String, Object> forwardOrigin = new LinkedHashMap<>();
        forwardOrigin.put("type", "hidden_user");
        forwardOrigin.put("sender_user_name", "Hidden Person");
        msg.put("forward_origin", forwardOrigin);
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.forwardedFrom()).isEqualTo("Hidden Person");
        assertThat(event.text()).startsWith("[Forwarded from @Hidden Person]:");
    }

    @Test
    void fromForwardedMessageWithForwardOriginChatUsesChatTitle() {
        Map<String, Object> update = baseUpdate(213L);
        Map<String, Object> msg = messageWithText(123L, 456L, "jdoe", "Channel forward");
        Map<String, Object> forwardOrigin = new LinkedHashMap<>();
        forwardOrigin.put("type", "chat");
        Map<String, Object> senderChat = new LinkedHashMap<>();
        senderChat.put("id", -100123);
        senderChat.put("title", "My Channel");
        forwardOrigin.put("sender_chat", senderChat);
        msg.put("forward_origin", forwardOrigin);
        update.put("message", msg);

        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.forwardedFrom()).isEqualTo("My Channel");
        assertThat(event.text()).startsWith("[Forwarded from @My Channel]:");
    }

    @Test
    void fromNonForwardedMessageHasNullForwardedFrom() {
        Map<String, Object> update = baseUpdate(214L);
        update.put("message", messageWithText(123L, 456L, "jdoe", "Regular message"));
        UpdateEvent event = UpdateEvent.from(update);

        assertThat(event.forwardedFrom()).isNull();
        assertThat(event.text()).isEqualTo("Regular message");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private Map<String, Object> baseUpdate(long updateId) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("update_id", updateId);
        return update;
    }

    private Map<String, Object> messageBase(long chatId, long userId, String username) {
        Map<String, Object> msg = new LinkedHashMap<>();
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("id", chatId);
        chat.put("type", "private");
        msg.put("chat", chat);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("id", userId);
        from.put("username", username);
        msg.put("from", from);
        return msg;
    }

    private Map<String, Object> messageWithText(long chatId, long userId, String username, String text) {
        Map<String, Object> msg = messageBase(chatId, userId, username);
        msg.put("text", text);
        return msg;
    }
}
