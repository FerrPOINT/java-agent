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
    String firstName,
    String languageCode,
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
    long messageThreadId,
    String forwardedFrom
) {

    public enum Type {
        TEXT, COMMAND, CALLBACK_QUERY, PHOTO, DOCUMENT, VOICE, STICKER, ANIMATION,
        LOCATION, CHANNEL_POST, EDITED_MESSAGE, UNKNOWN
    }

    /** Compact constructor — defaults firstName=null, languageCode=null, messageId=0, mediaGroupId=null, threadId=0, forwardedFrom=null. */
    public UpdateEvent(long updateId, Type type, long chatId, long userId,
                       String username, String text, String caption, String fileId, String fileType,
                       String callbackQueryId, String callbackData, String replyToText,
                       boolean isCommand, String commandName, String commandArgs) {
        this(updateId, type, chatId, userId, username, null, null, text, caption, fileId, fileType,
            callbackQueryId, callbackData, replyToText, isCommand, commandName, commandArgs,
            0L, null, 0L, null);
    }

    /** 17-arg constructor — defaults firstName=null, languageCode=null, forwardedFrom=null. */
    public UpdateEvent(long updateId, Type type, long chatId, long userId,
                       String username, String text, String caption, String fileId, String fileType,
                       String callbackQueryId, String callbackData, String replyToText,
                       boolean isCommand, String commandName, String commandArgs,
                       long messageId, String mediaGroupId, long messageThreadId) {
        this(updateId, type, chatId, userId, username, null, null, text, caption, fileId, fileType,
            callbackQueryId, callbackData, replyToText, isCommand, commandName, commandArgs,
            messageId, mediaGroupId, messageThreadId, null);
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
            String cqFirstName = from != null ? (String) from.get("first_name") : null;
            String cqLanguageCode = from != null ? (String) from.get("language_code") : null;
            String cqId = (String) callbackQuery.get("id");
            String data = (String) callbackQuery.get("data");
            return new UpdateEvent(updateId, Type.CALLBACK_QUERY, chatId, userId,
                username != null ? username : "", cqFirstName, cqLanguageCode,
                null, null, null, null,
                cqId, data, null, false, null, null,
                0L, null, 0L, null);
        }

        // Message
        Map<String, Object> message = (Map<String, Object>) update.get("message");
        boolean isEditedMessage = false;
        if (message == null) {
            // edited_message fallback
            message = (Map<String, Object>) update.get("edited_message");
            if (message != null) {
                isEditedMessage = true;
            }
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
            return new UpdateEvent(updateId, Type.UNKNOWN, 0, 0, "", null, null,
                null, null, null, null,  // text, caption, fileId, fileType
                null, null, null,         // callbackQueryId, callbackData, replyToText
                false, null, null,        // isCommand, commandName, commandArgs
                0L, null, 0L, null);      // messageId, mediaGroupId, messageThreadId, forwardedFrom
        }

        Map<String, Object> chat = (Map<String, Object>) message.get("chat");
        Map<String, Object> from = (Map<String, Object>) message.get("from");
        long chatId = chat != null ? ((Number) chat.get("id")).longValue() : 0L;
        long userId = from != null ? ((Number) from.get("id")).longValue() : 0L;
        String username = from != null ? (String) from.get("username") : "";
        String firstName = from != null ? (String) from.get("first_name") : null;
        String languageCode = from != null ? (String) from.get("language_code") : null;
        // Determine chat type from Telegram chat object
        String chatType = chat != null ? (String) chat.get("type") : "private";
        if (chatType == null) chatType = "private";
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
                // Audit L11: strip @botname suffix even when there are arguments.
                // Telegram groups add @botname to commands: /help@mybot args
                int atIdx = text.indexOf('@');
                if (atIdx > 0 && atIdx < spaceIdx) {
                    commandName = text.substring(1, atIdx);
                } else {
                    commandName = text.substring(1, spaceIdx);
                }
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
            // Audit L12: case-insensitive command name (Telegram autocapitalizes)
            commandName = commandName.toLowerCase();
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

        // Forward origin / forward_from — extract the forwarding username
        String forwardedFrom = extractForwardedFrom(message);

        // Check for media
        Type type = Type.UNKNOWN;
        // P2-20: If this is a channel_post, override the type
        if (isChannelPost) {
            type = Type.CHANNEL_POST;
        }
        // If this is an edited_message and no other type has been determined yet, mark as EDITED_MESSAGE
        if (isEditedMessage && type == Type.UNKNOWN) {
            type = Type.EDITED_MESSAGE;
        }
        String fileId = null;
        String fileType = null;
        if (text != null) {
            // If text is present, prefer the existing type (EDITED_MESSAGE/CHANNEL_POST) or determine command/text
            if (type == Type.UNKNOWN) {
                type = isCommand ? Type.COMMAND : Type.TEXT;
            }
            // For edited messages with text, keep the EDITED_MESSAGE type (overriding command detection)
            if (isEditedMessage) {
                type = Type.EDITED_MESSAGE;
                isCommand = false;
                commandName = null;
                commandArgs = null;
            }
        } else if (message.containsKey("photo")) {
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.PHOTO);
            if (!isEditedMessage && !isChannelPost) fileType = "photo";
            List<Map<String, Object>> photos = (List<Map<String, Object>>) message.get("photo");
            if (photos != null && !photos.isEmpty()) {
                // Get largest photo
                Map<String, Object> largest = photos.get(photos.size() - 1);
                fileId = (String) largest.get("file_id");
            }
        } else if (message.containsKey("document")) {
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.DOCUMENT);
            if (!isEditedMessage && !isChannelPost) fileType = "document";
            Map<String, Object> doc = (Map<String, Object>) message.get("document");
            fileId = doc != null ? (String) doc.get("file_id") : null;
        } else if (message.containsKey("voice")) {
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.VOICE);
            if (!isEditedMessage && !isChannelPost) fileType = "voice";
            Map<String, Object> voice = (Map<String, Object>) message.get("voice");
            fileId = voice != null ? (String) voice.get("file_id") : null;
        } else if (message.containsKey("sticker")) {
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.STICKER);
            if (!isEditedMessage && !isChannelPost) fileType = "sticker";
            Map<String, Object> sticker = (Map<String, Object>) message.get("sticker");
            fileId = sticker != null ? (String) sticker.get("file_id") : null;
        } else if (message.containsKey("animation")) {
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.ANIMATION);
            if (!isEditedMessage && !isChannelPost) fileType = "animation";
            Map<String, Object> anim = (Map<String, Object>) message.get("animation");
            fileId = anim != null ? (String) anim.get("file_id") : null;
        } else if (message.containsKey("location")) {
            // B3.6: Location message — parse lat/lon and format as text for LLM
            type = isEditedMessage ? Type.EDITED_MESSAGE : (isChannelPost ? Type.CHANNEL_POST : Type.LOCATION);
            if (!isEditedMessage && !isChannelPost) fileType = "location";
            Map<String, Object> location = (Map<String, Object>) message.get("location");
            if (location != null) {
                double lat = location.get("latitude") != null
                    ? ((Number) location.get("latitude")).doubleValue() : 0.0;
                double lon = location.get("longitude") != null
                    ? ((Number) location.get("longitude")).doubleValue() : 0.0;
                text = "Location: " + lat + ", " + lon;
            }
        }

        // Prefix message text with forward source attribution when present
        if (forwardedFrom != null && !forwardedFrom.isBlank() && text != null && !text.isBlank()) {
            text = "[Forwarded from @" + forwardedFrom + "]: " + text;
        } else if (forwardedFrom != null && !forwardedFrom.isBlank() && caption != null && !caption.isBlank()) {
            caption = "[Forwarded from @" + forwardedFrom + "]: " + caption;
        }

        return new UpdateEvent(updateId, type, chatId, userId,
            username != null ? username : "", firstName, languageCode,
            text, caption, fileId, fileType,
            null, null, replyToText, isCommand, commandName, commandArgs,
            msgId, mediaGroupId, threadId, forwardedFrom);
    }

    /**
     * Extract the forwarding username from {@code forward_origin} (Telegram Bot API 7.0+)
     * or fall back to the legacy {@code forward_from} field.
     *
     * @param message the message map
     * @return the forwarding username (without leading @), or null if not a forwarded message
     */
    @SuppressWarnings("unchecked")
    private static String extractForwardedFrom(Map<String, Object> message) {
        // Prefer forward_origin (new API) — has type-specific structure
        Map<String, Object> forwardOrigin = (Map<String, Object>) message.get("forward_origin");
        if (forwardOrigin != null) {
            String originType = (String) forwardOrigin.get("type");
            if ("user".equals(originType)) {
                Map<String, Object> senderUser = (Map<String, Object>) forwardOrigin.get("sender_user");
                if (senderUser != null) {
                    String uname = (String) senderUser.get("username");
                    if (uname != null && !uname.isBlank()) return uname;
                }
            } else if ("hidden_user".equals(originType)) {
                String senderName = (String) forwardOrigin.get("sender_user_name");
                if (senderName != null && !senderName.isBlank()) {
                    return sanitizeName(senderName);
                }
            } else if ("chat".equals(originType)) {
                Map<String, Object> senderChat = (Map<String, Object>) forwardOrigin.get("sender_chat");
                if (senderChat != null) {
                    String uname = (String) senderChat.get("username");
                    if (uname != null && !uname.isBlank()) return uname;
                    String title = (String) senderChat.get("title");
                    if (title != null && !title.isBlank()) return sanitizeName(title);
                }
            } else if ("channel".equals(originType)) {
                Map<String, Object> chat = (Map<String, Object>) forwardOrigin.get("chat");
                if (chat != null) {
                    String uname = (String) chat.get("username");
                    if (uname != null && !uname.isBlank()) return uname;
                    String title = (String) chat.get("title");
                    if (title != null && !title.isBlank()) return sanitizeName(title);
                }
            }
        }

        // Fallback to legacy forward_from field
        Map<String, Object> forwardFrom = (Map<String, Object>) message.get("forward_from");
        if (forwardFrom != null) {
            String uname = (String) forwardFrom.get("username");
            if (uname != null && !uname.isBlank()) return uname;
            String firstName = (String) forwardFrom.get("first_name");
            if (firstName != null && !firstName.isBlank()) return sanitizeName(firstName);
        }

        return null;
    }

    /**
     * Sanitize a display name for inclusion in a forward attribution prefix.
     * Strips characters that would break the "[Forwarded from @...]: " pattern.
     */
    private static String sanitizeName(String name) {
        // Keep alphanumeric, underscores, dashes, dots, spaces; strip the rest
        return name.replaceAll("[^A-Za-z0-9_.\\- ]", "").trim();
    }
}