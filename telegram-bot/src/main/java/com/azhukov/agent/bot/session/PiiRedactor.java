package com.azhukov.agent.bot.session;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Masks Telegram user IDs and chat IDs before injecting them into system prompts.
 *
 * <p>Replaces raw user IDs with deterministic hashes (e.g. {@code user_a3f2b1c9d8e7})
 * and chat IDs with hashed equivalents (e.g. {@code telegram:a3f2b1c9d8e7}).
 * Routing still uses the original values — redaction only affects what the LLM sees.
 *
 * <p>Mirrors the Python {@code _hash_id} / {@code _hash_sender_id} / {@code _hash_chat_id}
 * helpers in {@code gateway/session.py}.
 */
@Slf4j
public class PiiRedactor {

    private PiiRedactor() {
    }

    /**
     * Deterministic 12-char hex hash of an identifier.
     *
     * @param value the identifier to hash
     * @return 12-character hex string
     */
    public static String hashId(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= 12) break;
            }
            return hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            log.warn("SHA-256 not available, using fallback hash for PII redaction");
            // Fallback: simple hash (not cryptographically secure but deterministic)
            return String.format("%012x", (long) value.hashCode());
        }
    }

    /**
     * Hash a sender ID to {@code user_<12hex>}.
     *
     * @param userId raw user ID
     * @return hashed user ID, or null if input is null
     */
    public static String hashUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        return "user_" + hashId(userId);
    }

    /**
     * Hash the numeric portion of a chat ID, preserving platform prefix.
     *
     * <p>{@code telegram:12345} → {@code telegram:<hash>}<br>
     * {@code 12345} → {@code <hash>}
     *
     * @param chatId raw chat ID (may contain platform prefix)
     * @return hashed chat ID, or null if input is null
     */
    public static String hashChatId(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return null;
        }
        int colon = chatId.indexOf(':');
        if (colon > 0) {
            String prefix = chatId.substring(0, colon);
            String idPart = chatId.substring(colon + 1);
            return prefix + ":" + hashId(idPart);
        }
        return hashId(chatId);
    }

    /**
     * Build a redacted session context prompt.
     *
     * <p>Constructs a system-prompt section that tells the agent about its
     * current session context, with all user/chat IDs replaced by hashes.
     *
     * @param platform    platform name (e.g. "telegram")
     * @param userId      raw user ID
     * @param chatId      raw chat ID
     * @param username    Telegram username (may be null)
     * @param chatType    chat type: "dm", "group", "channel", "thread"
     * @param chatName    chat display name (may be null)
     * @return redacted session context prompt string
     */
    public static String buildRedactedContextPrompt(String platform, String userId,
                                                      String chatId, String username,
                                                      String chatType, String chatName) {
        StringBuilder lines = new StringBuilder();
        lines.append("## Current Session Context\n\n");

        String platformName = capitalize(platform);

        // Build a safe description without raw IDs
        String uname = (username != null && !username.isBlank())
            ? username
            : (userId != null ? hashUserId(userId) : "user");
        String cname = (chatName != null && !chatName.isBlank())
            ? chatName
            : (chatId != null ? hashChatId(chatId) : "unknown");

        String desc;
        if ("dm".equals(chatType)) {
            desc = "DM with " + uname;
        } else if ("group".equals(chatType)) {
            desc = "group: " + cname;
        } else if ("channel".equals(chatType)) {
            desc = "channel: " + cname;
        } else {
            desc = cname;
        }

        lines.append("**Source:** ").append(platformName).append(" (").append(desc).append(")\n");

        // User identity
        if (username != null && !username.isBlank()) {
            lines.append("**User:** ").append(username).append("\n");
        } else if (userId != null) {
            lines.append("**User ID:** ").append(hashUserId(userId)).append("\n");
        }

        return lines.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}