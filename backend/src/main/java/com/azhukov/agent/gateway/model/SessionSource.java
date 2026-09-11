package com.azhukov.agent.gateway.model;

/**
 * Identity of a conversation on a connected platform.
 *
 * <p>{@code threadId} covers forum topics / message threads (Hermes
 * {@code platform:chat_id:thread_id} targets). It is nullable: plain chats and
 * legacy call sites without thread information keep {@code null}.
 */
public record SessionSource(
    Platform platform,
    String chatId,
    String userId,
    String username,
    String displayName,
    String threadId
) {
    public SessionSource(Platform platform, String chatId, String userId, String username, String displayName) {
        this(platform, chatId, userId, username, displayName, null);
    }
}
