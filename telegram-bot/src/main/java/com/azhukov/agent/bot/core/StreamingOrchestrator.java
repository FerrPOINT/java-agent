package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.RuntimeFooter;
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
            // Start with an initial message (P2.S6: routed into the originating forum topic)
            Optional<Long> initialMsgId = streamEditor.startStream(chatId, "…", "dm", messageThreadId);
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
                    // Check for interrupt before processing tool call
                    if (busyHandler.isInterrupted(chatId)) {
                        log.debug("Stream interrupted during tool call for chat {}", chatId);
                        throw new StreamInterruptedException();
                    }
                    // Parse "toolName\u0001args" format from MessageApiClient
                    String toolName = toolCall;
                    String toolArgs = null;
                    int sep = toolCall.indexOf('\u0001');
                    if (sep >= 0) {
                        toolName = toolCall.substring(0, sep);
                        toolArgs = toolCall.substring(sep + 1);
                    }
                    streamEditor.setCurrentToolName(chatId, toolName);
                    // Finalize current streaming message with accumulated text (if any)
                    if (accumulated.length() > 0 && messageId[0] >= 0) {
                        streamEditor.onSegmentBreak(chatId, messageId[0], accumulated.toString());
                        accumulated.setLength(0);
                    }
                    // Hermes parity (gateway/display_config.py): Telegram defaults
                    // to tool_progress="off" — don't spam the chat with per-tool
                    // progress bubbles. Only send when explicitly enabled.
                    String toolProgress = properties.getDisplay().getToolProgress();
                    if (!"hidden".equalsIgnoreCase(toolProgress) && !"off".equalsIgnoreCase(toolProgress)) {
                        String toolDisplay = ToolEmojiMap.formatToolCall(toolName, toolArgs);
                        streamEditor.sendProgressMessage(chatId, toolDisplay);
                    }
                },
                // toolResultConsumer — called when backend emits tool_result event.
                // No segment break here: the text after a tool call is a NEW segment
                // that starts streaming via editStream (which creates a new message
                // because currentMessageId was reset to -1 by the previous segment break).
                // Calling onSegmentBreak here with stale messageId would edit the wrong
                // (already-finalized) message. Hermes does not break on tool_result either.
                (toolName, toolResultPreview) -> {
                    // Just clear accumulated text — the next tokens will start a new segment
                },
                // retryConsumer — called when backend emits retry/continuation events.
                // TRANSIENT status only (Hermes parity: gateway shows retry status, it never
                // becomes part of the answer): the display text is shown on the streaming
                // message but NOT appended to `accumulated`, so the finalized message and
                // ChatResult contain clean content. The next editStream with real tokens
                // overwrites the status line.
                retryMsg -> {
                    if (messageId[0] >= 0) {
                        String display = accumulated.length() > 0
                            ? accumulated + "\n\n" + retryMsg
                            : retryMsg;
                        streamEditor.editStream(chatId, messageId[0], display);
                    }
                },
                // onComplete
                result -> {
                    // If messageId is still -1 (startStream didn't send initial text because
                    // it was < 4 chars), but we have accumulated text, send it as a new message
                    // before finalizing. Hermes handles this via the 'off' transport fallback.
                    if (messageId[0] < 0 && accumulated.length() > 0) {
                        String display = accumulated.toString();
                        messageId[0] = streamEditor.startStream(chatId, display, "dm", messageThreadId)
                            .orElse(-1L);
                        // If startStream still returns empty (text < 4 chars), send directly
                        // via sendMessage to avoid losing the response (Hermes 'off' transport).
                        if (messageId[0] < 0) {
                            streamEditor.sendPlainMessage(chatId, display);
                            finalized[0] = true;
                        }
                    }
                    if (messageId[0] >= 0 && accumulated.length() > 0) {
                        // Append footer to the streaming message before finalizing
                        String footer = runtimeFooter.format(
                            hooks.resolveModelUsed(session, result),
                            result.contextTokens() != null ? result.contextTokens() : 0,
                            result.contextLength() != null ? result.contextLength() : 0,
                            properties.getWorkingDirectory()
                        );
                        String finalText = accumulated.toString();

                        // Reasoning display (Hermes parity: show_reasoning).
                        // When enabled, prepend the last reasoning/thinking block before the response.
                        if (properties.getReasoningDisplay().isEnabled() && result.lastReasoning() != null
                                && !result.lastReasoning().isBlank()) {
                            String reasoning = result.lastReasoning().strip();
                            // Collapse long reasoning to keep messages readable (Hermes: 15 lines)
                            var lines = reasoning.split("\\n");
                            if (lines.length > 15) {
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < 15; i++) sb.append(lines[i]).append("\n");
                                sb.append("_... (").append(lines.length - 15).append(" more lines)_");
                                reasoning = sb.toString();
                            }
                            String style = properties.getReasoningDisplay().getStyle();
                            String reasoningBlock;
                            if ("blockquote".equals(style)) {
                                StringBuilder sb = new StringBuilder("> 💭 **Reasoning:**\n");
                                for (String ln : reasoning.split("\\n")) {
                                    sb.append(ln.isEmpty() ? ">" : "> " + ln).append("\n");
                                }
                                reasoningBlock = sb.toString();
                            } else if ("subtext".equals(style)) {
                                StringBuilder sb = new StringBuilder("-# 💭 Reasoning\n");
                                for (String ln : reasoning.split("\\n")) {
                                    sb.append(ln.isEmpty() ? "-#" : "-# " + ln).append("\n");
                                }
                                reasoningBlock = sb.toString();
                            } else {
                                // "code" style (default): fenced code block
                                reasoningBlock = "💭 **Reasoning:**\n```\n" + reasoning + "\n```\n";
                            }
                            finalText = reasoningBlock + "\n" + finalText;
                        }

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
                        } else if (messageId[0] < 0) {
                            // Draft streaming (no message id): drop the draft session
                            // and its heartbeat so they don't leak.
                            streamEditor.clearStream(chatId);
                        }
                    } else {
                        log.error("Stream error for chat {}: {}", chatId, error.getMessage());
                        // Finalize with partial content + user-friendly error message
                        String userFriendlyError = toUserFriendlyError(error);
                        String errorText = accumulated.length() > 0
                            ? accumulated + "\n\n" + userFriendlyError
                            : userFriendlyError;
                        if (messageId[0] >= 0) {
                            streamEditor.finalizeStream(chatId, messageId[0], errorText);
                            finalized[0] = true;
                        } else {
                            // P0: no streaming message exists (draft streaming keeps
                            // messageId at -1 until the first token arrives; a model
                            // error before any token left the user with NO feedback at
                            // all). Deliver the error text as a standalone message so
                            // the user is never left in silence. clearStream drops the
                            // draft StreamSession and its heartbeat.
                            streamEditor.sendPlainMessage(chatId, errorText);
                            streamEditor.clearStream(chatId);
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
        // Billing / usage-limit errors (Hermes parity: BILLING errors are
        // non-retryable — the user must see a clear explanation, not "Temporary issue").
        // Disambiguation mirrors ErrorClassifier: "usage limit" WITH a transient
        // signal ("try again", "resets at") is a rate limit; without it it's billing.
        if (msg.contains("usage limit") || msg.contains("billing")
            || msg.contains("quota") || msg.contains("insufficient")
            || msg.contains("add extra usage") || msg.contains("credit")) {
            if (msg.contains("try again") || msg.contains("resets at")
                || msg.contains("rate limit")) {
                return "Rate limited. Please try again later.";
            }
            return "Provider usage limit reached (billing). Add usage at the provider settings page or switch the model.";
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