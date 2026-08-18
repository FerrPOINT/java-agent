package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.footer.RuntimeFooter;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.streaming.ToolEmojiMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * c5: Streaming chat orchestration — owns the streaming lifecycle and the
 * SSE callback wiring between {@link AgentBackendClient#chatStream} and
 * {@link StreamEditor}.
 *
 * <p>Extracted from {@code BotMessageProcessor#streamChat} so the processor
 * can stay a thin orchestrator. This service:
 * <ul>
 *   <li>Starts the streaming message via {@link StreamEditor#startStream}</li>
 *   <li>Wires the token / tool-call / tool-result / retry / complete / error
 *       callbacks to {@link StreamEditor}</li>
 *   <li>Appends the runtime footer and extracts MEDIA: tags before finalize</li>
 *   <li>Handles the sync fallback when the stream produced no visible tokens</li>
 *   <li>Cleans up the streaming message on failure</li>
 * </ul>
 *
 * <p>The interrupt check ({@link BusySessionHandler#isInterrupted}) is honored
 * inside the token consumer, throwing {@link StreamInterruptedException} to
 * break out of the stream loop.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamingOrchestrator {

    private final AgentBackendClient backendClient;
    private final StreamEditor streamEditor;
    private final BusySessionHandler busyHandler;
    private final RuntimeFooter runtimeFooter;
    private final BotProperties properties;
    private final MediaDeliveryService mediaDeliveryService;

    /** Internal exception to break out of streaming on interrupt. */
    static class StreamInterruptedException extends RuntimeException {
        StreamInterruptedException() { super("Stream interrupted"); }
    }

    /**
     * Callbacks the orchestrator needs from the processor, to avoid duplicating
     * media delivery and model-resolution logic.
     */
    public interface ProcessorHooks {
        /** Resolve the model name used for the footer (override → result → default). */
        String resolveModelUsed(BotSessionEntity session, AgentBackendClient.ChatResult result);
        /** Deliver extracted media files as native Telegram attachments. */
        void deliverMedia(long chatId, List<MediaDeliveryService.MediaDescriptor> media, Integer threadId);
        /** Build the message with an optional PII-redacted session-context prefix. */
        String buildMessageWithContext(String messageText, BotSessionEntity session, long chatId);
    }

    /**
     * Stream chat from the backend, editing the message in-place via StreamEditor.
     * Falls back to synchronous chat() if streaming fails to start.
     * Checks for interrupts during streaming.
     *
     * @param chatId the Telegram chat ID
     * @param messageText the user's message
     * @param sessionId the session UUID string (may be null)
     * @param session the bot session (for footer model resolution)
     * @param userMessageId the user's message ID (for reply)
     * @param messageThreadId the Telegram forum thread ID (0 = no thread routing)
     * @param hooks callbacks into the processor (media delivery, model resolution, PII prefix)
     * @return the full accumulated response content and metadata
     */
    public AgentBackendClient.ChatResult streamChat(long chatId, String messageText, String sessionId,
                                                     BotSessionEntity session, long userMessageId,
                                                     long messageThreadId, ProcessorHooks hooks) {
        // P0: PII Redaction — prepend redacted session context to the message
        String fullMessage = hooks.buildMessageWithContext(messageText, session, chatId);
        StringBuilder accumulated = new StringBuilder(); // clean LLM text only
        final long[] messageId = {-1};
        final boolean[] finalized = {false};

        // Try streaming first
        try {
            // Start with an initial message
            Optional<Long> initialMsgId = streamEditor.startStream(chatId, "…");
            if (initialMsgId.isPresent()) {
                messageId[0] = initialMsgId.get();
            }

            AgentBackendClient.ChatResult streamResult = backendClient.chatStream(fullMessage, sessionId, session,
                // token consumer
                token -> {
                    accumulated.append(token);
                    // Check for interrupt
                    if (busyHandler.isInterrupted(chatId)) {
                        log.debug("Stream interrupted for chat {}", chatId);
                        throw new StreamInterruptedException();
                    }
                    // Edit the message with accumulated text (throttled by StreamEditor)
                    // editStream handles both edit-based and draft streaming internally
                    streamEditor.editStream(chatId, Math.max(0, messageId[0]), accumulated.toString());
                },
                // toolCallConsumer — called when backend emits tool_calls event
                // Hermes parity: send tool call as a separate short message (progress bubble),
                // not accumulated in the main text. This keeps the chat clean and readable.
                toolCall -> {
                    streamEditor.setCurrentToolName(chatId, toolCall);
                    // Finalize current streaming message with accumulated text (if any)
                    if (accumulated.length() > 0 && messageId[0] >= 0) {
                        streamEditor.onSegmentBreak(chatId, messageId[0], accumulated.toString());
                        accumulated.setLength(0);
                    }
                    // Send tool call as a separate message (progress bubble)
                    String toolDisplay = ToolEmojiMap.formatToolCall(toolCall, null);
                    streamEditor.sendProgressMessage(chatId, toolDisplay);
                },
                // toolResultConsumer — called when backend emits tool_result event
                (toolName, toolResultPreview) -> {
                    if (messageId[0] >= 0) {
                        streamEditor.onSegmentBreak(chatId, messageId[0], accumulated.toString());
                    }
                },
                // retryConsumer — called when backend emits retry/continuation events
                retryMsg -> {
                    if (messageId[0] >= 0) {
                        // Update streaming message to show retry status to the user
                        String display = accumulated.length() > 0
                            ? accumulated + "\n\n" + retryMsg
                            : retryMsg;
                        streamEditor.editStream(chatId, messageId[0], display);
                    }
                },
                // onComplete
                result -> {
                    if (messageId[0] >= 0 && accumulated.length() > 0) {
                        // Append footer to the streaming message before finalizing
                        String footer = runtimeFooter.format(
                            hooks.resolveModelUsed(session, result),
                            result.contextTokens() != null ? result.contextTokens() : 0,
                            result.contextLength() != null ? result.contextLength() : 0,
                            properties.getWorkingDirectory()
                        );
                        String finalText = accumulated.toString();
                        if (!footer.isEmpty()) {
                            finalText = finalText + footer;
                        }
                        // S-2: Extract MEDIA: tags before finalizing the stream message
                        // so the displayed text doesn't contain raw MEDIA: tags
                        if (properties.isMediaDeliveryEnabled()) {
                            MediaDeliveryService.ExtractionResult extraction = mediaDeliveryService.extractMediaTags(finalText);
                            finalText = extraction.cleanedText();
                            // Finalize the stream with cleaned text
                            streamEditor.finalizeStream(chatId, messageId[0], finalText);
                            finalized[0] = true;
                            // Deliver extracted media files as native Telegram attachments
                            if (!extraction.media().isEmpty()) {
                                Integer mediaThreadId = messageThreadId > 0 ? (int) messageThreadId : null;
                                hooks.deliverMedia(chatId, extraction.media(), mediaThreadId);
                            }
                        } else {
                            streamEditor.finalizeStream(chatId, messageId[0], finalText);
                            finalized[0] = true;
                        }
                    }
                },
                // onError
                error -> {
                    if (error instanceof StreamInterruptedException) {
                        // Interrupted — finalize with accumulated content (no raw error text)
                        if (messageId[0] >= 0 && accumulated.length() > 0) {
                            streamEditor.finalizeStream(chatId, messageId[0],
                                accumulated.toString());
                            finalized[0] = true;
                        }
                    } else {
                        log.error("Stream error for chat {}: {}", chatId, error.getMessage());
                        // Finalize with partial content + user-friendly error message
                        if (messageId[0] >= 0) {
                            String userFriendlyError = toUserFriendlyError(error);
                            String errorText = accumulated.length() > 0
                                ? accumulated + "\n\n" + userFriendlyError
                                : userFriendlyError;
                            streamEditor.finalizeStream(chatId, messageId[0], errorText);
                            finalized[0] = true;
                        }
                    }
                }
            );

            // If streaming produced content, return it (with metadata from the stream)
            if (accumulated.length() > 0 || finalized[0]) {
                return new AgentBackendClient.ChatResult(
                    accumulated.toString(),
                    streamResult.modelUsed(),
                    streamResult.contextTokens(),
                    streamResult.contextLength(),
                    finalized[0],
                    streamResult.memoryUpdated(),
                    streamResult.backendSessionId()
                );
            }
            // If streaming produced no visible tokens but has metadata, prefer the sync fallback to get content
            if (streamResult.modelUsed() != null || streamResult.contextTokens() != null) {
                return fallbackSyncWithMetadata(fullMessage, sessionId, session, streamResult);
            }
            // Stream finished but produced no content and no metadata
            return new AgentBackendClient.ChatResult(accumulated.toString(),
                streamResult.modelUsed(), streamResult.contextTokens(), streamResult.contextLength(), false,
                streamResult.memoryUpdated(), streamResult.backendSessionId());
        } catch (StreamInterruptedException e) {
            // Already handled in onError callback
            return new AgentBackendClient.ChatResult(accumulated.toString(), null, null, null, finalized[0], false);
        } catch (Exception e) {
            log.warn("Streaming failed for chat {}: {}", chatId, e.getMessage());
            // Clean up the initial message if streaming failed
            if (messageId[0] >= 0) {
                streamEditor.clearStream(chatId);
            }
            throw new RuntimeException("Streaming failed: " + e.getMessage(), e);
        }
    }

    private AgentBackendClient.ChatResult fallbackSyncWithMetadata(String messageText, String sessionId,
                                                                    BotSessionEntity session,
                                                                    AgentBackendClient.ChatResult streamResult) {
        AgentBackendClient.ChatResult syncResult = backendClient.chat(messageText, sessionId, session);
        return new AgentBackendClient.ChatResult(
            syncResult.content(),
            syncResult.modelUsed() != null ? syncResult.modelUsed() : streamResult.modelUsed(),
            syncResult.contextTokens() != null ? syncResult.contextTokens() : streamResult.contextTokens(),
            syncResult.contextLength() != null ? syncResult.contextLength() : streamResult.contextLength(),
            false,
            syncResult.memoryUpdated() || streamResult.memoryUpdated(),
            syncResult.backendSessionId() != null ? syncResult.backendSessionId() : streamResult.backendSessionId()
        );
    }

    /**
     * Convert a stream error into a user-friendly message, matching Hermes behavior.
     * Instead of showing raw [Error: msg], provide contextual messages:
     * - Rate limit errors → "Rate limited by Telegram. Retrying..."
     * - Network/timeout errors → "Network issue. Retrying..."
     * - Auth/config errors → "Configuration issue. Contact admin."
     * - Generic errors → "Temporary issue. Please try again."
     *
     * <p>Package-private for testability.
     *
     * @param error the exception from the stream
     * @return a user-friendly error message
     */
    static String toUserFriendlyError(Throwable error) {
        if (error == null) {
            return "Temporary issue. Please try again.";
        }
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        String className = error.getClass().getSimpleName().toLowerCase();

        // Rate limit / flood control
        if (msg.contains("rate limit") || msg.contains("flood") || msg.contains("429")
            || msg.contains("too many requests") || msg.contains("retry after")) {
            return "Rate limited by Telegram. Retrying...";
        }
        // Network / timeout errors
        if (error instanceof java.util.concurrent.TimeoutException
            || error instanceof java.net.SocketTimeoutException
            || className.contains("timeout")
            || msg.contains("timeout") || msg.contains("timed out")
            || msg.contains("connection") || msg.contains("network")
            || msg.contains("unreachable") || msg.contains("reset")) {
            return "Network issue. Retrying...";
        }
        // Auth / configuration errors
        if (msg.contains("unauthorized") || msg.contains("auth") || msg.contains("forbidden")
            || msg.contains("api key") || msg.contains("token") || msg.contains("401")
            || msg.contains("403") || msg.contains("permission")) {
            return "Configuration issue. Contact admin.";
        }
        // Generic fallback
        return "Temporary issue. Please try again.";
    }
}