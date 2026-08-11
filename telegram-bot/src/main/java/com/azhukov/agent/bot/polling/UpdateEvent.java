package com.azhukov.agent.bot.polling;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed Telegram update event.
 */
public record UpdateEvent(
    long updateId,
    Type type,
    long chatId,
    long userId,
    String username,
    String text,
    String caption,
    String fileId,
    String fileType, // photo, document, voice, sticker, animation
    String callbackQueryId,
    String callbackData,
    String replyToText,
    boolean isCommand,
    String commandName,
    String commandArgs,
    long messageId,
    String mediaGroupId,
    long messageThreadId
) {

    public enum Type {
        TEXT, COMMAND, CALLBACK_QUERY, PHOTO, DOCUMENT, VOICE, STICKER, ANIMATION, LOCATION, CHANNEL_POST, UNKNOWN
    }

    /** Compact constructor — defaults messageId=0, mediaGroupId=null for backward compatibility. */
    public UpdateEvent(long updateId, Type type, long chatId, long userId,
                       String username, String text, String caption, String fileId, String fileType,
                       String callbackQueryId, String callbackData, String replyToText,
                       boolean isCommand, String commandName, String commandArgs) {
        this(updateId, type, chatId, userId, username, text, caption, fileId, fileType,
            callbackQueryId, callbackData, replyToText, isCommand, commandName, commandArgs,
            0L, null, 0L);
    }

    @SuppressWarnings("unchecked")
    public static UpdateEvent from(Map<String, Object> update) {
        long updateId = ((Number) update.get("update_id")).longValue();

        // Callback query
        Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
        if (callbackQuery != null) {
            Map<String, Object> from = (Map<String, Object>) callbackQuery.get("from");
            Map<String, Object> message = (Map<String, Object>) callbackQuery.get("message");
            long chatId = 0L;
            if (message != null) {
                Map<String, Object> chat = (Map<String, Object>) message.get("chat");
                chatId = chat != null ? ((Number) chat.get("id")).longValue() : 0L;
            }
            long userId = from != null ? ((Number) from.get("id")).longValue() : 0L;
            String username = from != null ? (String) from.get("username") : "";
            String cqId = (String) callbackQuery.get("id");
            String data = (String) callbackQuery.get("data");
            return new UpdateEvent(updateId, Type.CALLBACK_QUERY, chatId, userId,
                username != null ? username : "", null, null, null, null,
                cqId, data, null, false, null, null);
        }

        // Message
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        if (message == null) {
            // edited_message fallback
            message = (Map<String, Object>) update.get("edited_message");
        }
        // P2-20: channel_post — parse like a regular message but mark as CHANNEL_POST type
        boolean isChannelPost = false;
        if (message == null) {
            message = (Map<String, Object>) update.get("channel_post");
            if (message != null) {
                isChannelPost = true;
            }
        }
        // P2-20: edited_channel_post fallback
        if (message == null) {
            message = (Map<String, Object>) update.get("edited_channel_post");
            if (message != null) {
                isChannelPost = true;
            }
        }
        if (message == null) {
            return new UpdateEvent(updateId, Type.UNKNOWN, 0, 0, "", null, null, null, null,
                null, null, null, false, null, null);
        }

        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        long chatId = chat != null ? ((Number) chat.get("id")).longValue() : 0L;
        long userId = from != null ? ((Number) from.get("id")).longValue() : 0L;
        String username = from != null ? (String) from.get("username") : "";
        String text = Optional.ofNullable(message.get("text")).map(Object::toString).orElse(null);
        String caption = Optional.ofNullable(message.get("caption")).map(Object::toString).orElse(null);

        // Reply to message
        String replyToText = null;
        Map<String, Object> replyToMessage = (Map<String, Object>) message.get("reply_to_message");
        if (replyToMessage != null) {
            replyToText = Optional.ofNullable(replyToMessage.get("text")).map(Object::toString).orElse(null);
        }

        // Check for command
        boolean isCommand = false;
        String commandName = null;
        String commandArgs = null;
        if (text != null && text.startsWith("/")) {
            isCommand = true;
            int spaceIdx = text.indexOf(' ');
            if (spaceIdx > 0) {
                commandName = text.substring(1, spaceIdx);
                commandArgs = text.substring(spaceIdx + 1).trim();
            } else {
                // Strip @botname suffix
                int atIdx = text.indexOf('@');
                if (atIdx > 0) {
                    commandName = text.substring(1, atIdx);
                } else {
                    commandName = text.substring(1);
                }
                commandArgs = "";
            }
        }

        // Message ID
        long msgId = message.get("message_id") != null
            ? ((Number) message.get("message_id")).longValue() : 0L;

        // Media group ID
        String mediaGroupId = Optional.ofNullable(message.get("media_group_id"))
            .map(Object::toString).orElse(null);

        // Forum topic thread ID (message_thread_id in Telegram API)
        long threadId = message.get("message_thread_id") != null
            ? ((Number) message.get("message_thread_id")).longValue() : 0L;

        // Check for media
        Type type = Type.UNKNOWN;
        // P2-20: If this is a channel_post, override the type
        if (isChannelPost) {
            type = Type.CHANNEL_POST;
        }
        String fileId = null;
        String fileType = null;
        if (text != null) {
            type = isChannelPost ? Type.CHANNEL_POST : (isCommand ? Type.COMMAND : Type.TEXT);
        } else if (message.containsKey("photo")) {
            type = Type.PHOTO;
            fileType = "photo";
            List<Map<String, Object>> photos = (List<Map<String, Object>>) message.get("photo");
            if (photos != null && !photos.isEmpty()) {
                // Get largest photo
                Map<String, Object> largest = photos.get(photos.size() - 1);
                fileId = (String) largest.get("file_id");
            }
        } else if (message.containsKey("document")) {
            type = Type.DOCUMENT;
            fileType = "document";
            Map<String, Object> doc = (Map<String, Object>) message.get("document");
            fileId = doc != null ? (String) doc.get("file_id") : null;
        } else if (message.containsKey("voice")) {
            type = Type.VOICE;
            fileType = "voice";
            Map<String, Object> voice = (Map<String, Object>) message.get("voice");
            fileId = voice != null ? (String) voice.get("file_id") : null;
        } else if (message.containsKey("sticker")) {
            type = Type.STICKER;
            fileType = "sticker";
            Map<String, Object> sticker = (Map<String, Object>) message.get("sticker");
            fileId = sticker != null ? (String) sticker.get("file_id") : null;
        } else if (message.containsKey("animation")) {
            type = Type.ANIMATION;
            fileType = "animation";
            Map<String, Object> anim = (Map<String, Object>) message.get("animation");
            fileId = anim != null ? (String) anim.get("file_id") : null;
        } else if (message.containsKey("location")) {
            // B3.6: Location message — parse lat/lon and format as text for LLM
            type = Type.LOCATION;
            fileType = "location";
            Map<String, Object> location = (Map<String, Object>) message.get("location");
            if (location != null) {
                double lat = location.get("latitude") != null
                    ? ((Number) location.get("latitude")).doubleValue() : 0.0;
                double lon = location.get("longitude") != null
                    ? ((Number) location.get("longitude")).doubleValue() : 0.0;
                text = "Location: " + lat + ", " + lon;
            }
        }

        return new UpdateEvent(updateId, type, chatId, userId,
            username != null ? username : "", text, caption, fileId, fileType,
            null, null, replyToText, isCommand, commandName, commandArgs,
            msgId, mediaGroupId, threadId);
    }
}