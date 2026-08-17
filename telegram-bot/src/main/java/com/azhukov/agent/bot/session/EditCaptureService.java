package com.azhukov.agent.bot.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P35: Tracks which chats are in "edit-capture" mode — a state where the next
 * text message from that chat should be routed to the edit-capture handler
 * instead of normal message processing (e.g. capturing edited message content
 * for an approval flow).
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}.
 */
@Service
@Slf4j
public class EditCaptureService {

    /**
     * Immutable context for an active edit-capture session.
     *
     * @param approvalId the integer approval ID associated with this capture
     * @param startedAt  epoch millis when the capture was started
     */
    public record CaptureContext(int approvalId, long startedAt) {}

    private final Map<Long, CaptureContext> captures = new ConcurrentHashMap<>();

    /**
     * Start edit-capture mode for a chat.
     *
     * @param chatId  the Telegram chat ID
     * @param context the capture context (approval ID + timestamp)
     */
    public void startCapture(long chatId, CaptureContext context) {
        captures.put(chatId, context);
        log.debug("Started edit-capture for chat {}, approvalId={}", chatId, context.approvalId());
    }

    /**
     * Get the active capture context for a chat, if any.
     *
     * @param chatId the Telegram chat ID
     * @return the capture context, or {@code null} if chat is not in capture mode
     */
    public CaptureContext getCapture(long chatId) {
        return captures.get(chatId);
    }

    /**
     * End edit-capture mode for a chat.
     *
     * @param chatId the Telegram chat ID
     */
    public void endCapture(long chatId) {
        CaptureContext removed = captures.remove(chatId);
        if (removed != null) {
            log.debug("Ended edit-capture for chat {}, approvalId={}", chatId, removed.approvalId());
        }
    }
}