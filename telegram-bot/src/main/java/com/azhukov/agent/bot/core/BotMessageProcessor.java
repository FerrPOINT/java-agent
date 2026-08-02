package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.commands.impl.GoalCommand;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.footer.RuntimeFooter;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.azhukov.agent.bot.formatting.ResponseFilter;
import com.azhukov.agent.bot.goal.GoalAutoContinueService;
import com.azhukov.agent.bot.group.GroupMessageFilter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.reaction.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.session.PiiRedactor;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

/**
 * Main message processor — the orchestrator for all incoming {@link UpdateEvent}s.
 *
 * <p>Flow:
 * <ol>
 *   <li>If callback_query → route to {@link CallbackQueryHandler}</li>
 *   <li>If command → route to {@link CommandRegistry}, send response via {@link TelegramClient}</li>
 *   <li>If text/media → check authorization, resolve session, check busy state,
 *       start typing, call {@link AgentBackendClient} (streaming via {@link StreamEditor}),
 *       stop typing, format via {@link MarkdownConverter} + {@link MessageSplitter}, send</li>
 *   <li>On error → send error message to chat</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BotMessageProcessor implements Consumer<UpdateEvent> {

    private final TelegramClient telegramClient;
    private final AuthorizationService authorizationService;
    private final BotSessionStore sessionStore;
    private final BusySessionHandler busyHandler;
    private final TypingManager typingManager;
    private final AgentBackendClient backendClient;
    private final CommandRegistry commandRegistry;
    private final CallbackQueryHandler callbackQueryHandler;
    private final BotProperties properties;
    private final StreamEditor streamEditor;
    private final InboundMediaHandler inboundMediaHandler;
    private final RuntimeFooter runtimeFooter;
    private final ReactionManager reactionManager;
    private final TextBatchDebouncer textBatchDebouncer;
    private final PhotoBatchDebouncer photoBatchDebouncer;
    private final GroupMessageFilter groupMessageFilter;
    private final SlashAccessPolicy slashAccessPolicy;
    private final ResponseFilter responseFilter;
    private final GoalAutoContinueService goalAutoContinueService;

    /**
     * B1.3: Called by TextBatchDebouncer when a batch is ready to dispatch.
     */
    private void dispatchTextBatch(UpdateEvent mergedEvent) {
        handleTextOrMedia(mergedEvent);
    }

    /**
     * B1.4: Called by PhotoBatchDebouncer when a photo group is ready to dispatch.
     */
    private void dispatchPhotoBatch(UpdateEvent mergedEvent) {
        handleTextOrMedia(mergedEvent);
    }

    @Override
    public void accept(UpdateEvent event) {
        if (event == null) return;

        try {
            switch (event.type()) {
                case CALLBACK_QUERY -> handleCallbackQuery(event);
                case COMMAND -> handleCommand(event);
                case TEXT -> {
                    // B1.3: Text batch/debounce
                    if (properties.getTextBatch() != null && offerTextBatch(event)) {
                        return; // Buffered, will be dispatched later
                    }
                    handleTextOrMedia(event);
                }
                case PHOTO -> {
                    // B1.4/B1.5: Photo batch/album — if mediaGroupId present, batch
                    if (event.mediaGroupId() != null && !event.mediaGroupId().isBlank()
                        && offerPhotoBatch(event)) {
                        return; // Buffered, will be dispatched later
                    }
                    handleTextOrMedia(event);
                }
                case DOCUMENT, VOICE, STICKER, ANIMATION -> handleTextOrMedia(event);
                case UNKNOWN -> log.debug("Ignoring UNKNOWN update: {}", event.updateId());
            }
        } catch (Exception e) {
            log.error("Error processing update {}: {}", event.updateId(), e.getMessage(), e);
            sendError(event.chatId(), "An error occurred while processing your message.");
        }
    }

    /**
     * B1.3: Offer text event to debouncer. Returns true if buffered.
     */
    private boolean offerTextBatch(UpdateEvent event) {
        // Only batch pure TEXT events (not commands, which are handled separately)
        if (event.isCommand()) return false;
        return textBatchDebouncer.offer(event);
    }

    /**
     * B1.4/B1.5: Offer photo event to batch debouncer. Returns true if buffered.
     */
    private boolean offerPhotoBatch(UpdateEvent event) {
        return photoBatchDebouncer.offer(event);
    }

    // ─── Callback Query ────────────────────────────────────────────

    private void handleCallbackQuery(UpdateEvent event) {
        String response = callbackQueryHandler.handle(event);
        if (response != null && !response.isBlank()) {
            telegramClient.sendMessage(event.chatId(), response);
        }
    }

    // ─── Command ───────────────────────────────────────────────────

    private void handleCommand(UpdateEvent event) {
        String commandName = event.commandName();
        CommandHandler handler = commandRegistry.get(commandName);
        if (handler == null) {
            telegramClient.sendMessage(event.chatId(),
                "Unknown command: /" + commandName + "\nUse /help to see available commands.");
            return;
        }

        if (!slashAccessPolicy.canRun(event.userId(), commandName)) {
            telegramClient.sendMessage(event.chatId(),
                "⛔ You don't have access to /" + commandName);
            return;
        }

        try {
            BotSessionEntity session = resolveSession(event);
            String response = handler.handle(event, session);
            if (response != null && !response.isBlank()) {
                sendFormatted(event.chatId(), response);
            }
        } catch (Exception e) {
            log.error("Command /{} failed: {}", commandName, e.getMessage(), e);
            sendError(event.chatId(), "Error executing command: " + e.getMessage());
        }
    }

    // ─── Text / Media ──────────────────────────────────────────────

    private void handleTextOrMedia(UpdateEvent event) {
        handleTextOrMediaInternal(event);
        // Drain queued messages after top-level processing (not recursive)
        drainQueue(event.chatId());
    }

    private void handleTextOrMediaInternal(UpdateEvent event) {
        long chatId = event.chatId();

        // B1.8/B1.9: Group mention requirement + guest mode
        if (!groupMessageFilter.shouldProcess(event)) {
            // B2.6: If observe-unmentioned is enabled, store message as context
            if (groupMessageFilter.shouldObserveUnmentioned()) {
                String observationText = groupMessageFilter.getObservationText(event);
                if (observationText != null && !observationText.isBlank()) {
                    log.debug("Observing unmentioned message in group chat {}", chatId);
                    // Store as context — in a full implementation this would call
                    // backend to append to session context without triggering a response.
                    // For now, just log it as context observation.
                }
            }
            log.debug("Skipping message in group chat {} — bot not mentioned", chatId);
            return;
        }

        // Authorization check
        if (!authorizationService.isAuthorized(event)) {
            log.debug("Unauthorized message from userId={} username={} chatId={}",
                event.userId(), event.username(), chatId);
            // B2.5: If pairing is enabled, generate a pairing code
            if (authorizationService.isPairingEnabled()) {
                java.util.Optional<String> code = authorizationService.generatePairingCode(
                    event.userId(), event.username(), chatId);
                if (code.isPresent()) {
                    telegramClient.sendMessage(chatId,
                        "🔐 You are not authorized. Send this code to the bot owner: " + code.get());
                } else {
                    telegramClient.sendMessage(chatId,
                        "⛔ You are not authorized. Pairing code limit reached. Contact the bot owner.");
                }
            } else {
                telegramClient.sendMessage(chatId,
                    "⛔ You are not authorized to use this bot.");
            }
            return;
        }

        // Resolve session
        BotSessionEntity session = resolveSession(event);

        // Build message text from the event — now uses InboundMediaHandler for media
        String messageText = extractMessageText(event);
        if (messageText == null || messageText.isBlank()) {
            log.debug("No text content in update {}, skipping", event.updateId());
            return;
        }

        // Check busy state
        if (busyHandler.isBusy(chatId)) {
            String busyMode = busyHandler.getBusyMode();
            if ("interrupt".equalsIgnoreCase(busyMode)) {
                busyHandler.interrupt(chatId);
                // Queue the interrupting message for re-processing after the current turn stops
                busyHandler.queueMessage(chatId, event);
                log.debug("Interrupting busy chat {} and queued interrupting message", chatId);
                return;
            } else {
                busyHandler.queueMessage(chatId, event);
                log.debug("Queued message for busy chat {}", chatId);
                return;
            }
        }

        // B1.2: Reaction — processing start
        reactionManager.onProcessingStart(chatId, event.messageId());

        // Process the turn
        busyHandler.markBusy(chatId);
        typingManager.startTyping(chatId);

        AgentBackendClient.ChatResult result;
        try {
            String sessionId = session.getId() != null ? session.getId().toString() : null;

            // Build footer text (will be appended to streaming message or sync response)
            result = streamChat(chatId, messageText, sessionId, session, event.messageId());

            // If streaming produced content and finalized a message, don't send duplicate
            if (!result.streamFinalized()) {
                // Streaming didn't finalize (failed or no content) — send via sendFormatted
                String footer = runtimeFooter.format(
                    resolveModelUsed(session, result),
                    result.contextTokens() != null ? result.contextTokens() : 0,
                    result.contextLength() != null ? result.contextLength() : 0,
                    properties.getWorkingDirectory()
                );
                String backendResponse = result.content();
                if (!footer.isEmpty()) {
                    backendResponse = backendResponse + footer;
                }

                typingManager.flushTyping(chatId);
                sendFormatted(chatId, backendResponse, event.messageId());
            }

            // Voice mode: synthesize TTS and send as voice message
            if (session.isVoiceMode() && result.content() != null && !result.content().isBlank()) {
                sendVoiceResponse(chatId, result.content());
            }

            // 💾 Memory updated notification (Hermes parity)
            if (result.memoryUpdated()) {
                sendFormatted(chatId, "💾 Self-improvement review: Memory updated", event.messageId());
            }

            // P0: Standing goal auto-continuation
            if (properties.getGoalAutoContinue().isEnabled()
                && GoalCommand.getActiveGoal(session) != null
                && !busyHandler.isInterrupted(chatId)) {
                final long chatIdForLambda = chatId;
                List<String> continuations = goalAutoContinueService.runAutoContinue(
                    session, result.content(), () -> busyHandler.isInterrupted(chatIdForLambda));
                for (String continuation : continuations) {
                    if (continuation != null && !continuation.isBlank()) {
                        sendFormatted(chatId, continuation, event.messageId());
                    }
                }
            }

            // B1.2: Reaction — processing complete (success or cancelled)
            if (busyHandler.isInterrupted(chatId)) {
                reactionManager.onCancel(chatId, event.messageId());
            } else {
                reactionManager.onProcessingComplete(chatId, event.messageId(), true);
            }
            typingManager.stopTyping(chatId);
        } catch (Exception e) {
            log.error("Backend call failed for chat {}: {}", chatId, e.getMessage(), e);
            typingManager.flushTyping(chatId);
            sendError(chatId, "Error contacting the agent backend: " + e.getMessage());
            // B1.2: Reaction — processing complete (failure)
            reactionManager.onProcessingComplete(chatId, event.messageId(), false);
            typingManager.stopTyping(chatId);
        } finally {
            busyHandler.markFree(chatId);
        }
    }

    /**
     * Drain queued messages for a chat — linear, not recursive.
     * Uses {@link #handleTextOrMediaInternal} to avoid recursive drain calls.
     * Guards against infinite loops with a max drain depth.
     */
    private void drainQueue(long chatId) {
        int maxDrainDepth = 100;
        int drained = 0;
        while (busyHandler.hasQueued(chatId) && drained < maxDrainDepth) {
            List<UpdateEvent> queued = busyHandler.drainQueue(chatId);
            for (UpdateEvent queuedEvent : queued) {
                try {
                    handleTextOrMediaInternal(queuedEvent);
                } catch (Exception e) {
                    log.error("Error draining queued message for chat {}: {}", chatId, e.getMessage(), e);
                }
                drained++;
                if (drained >= maxDrainDepth) {
                    log.warn("Queue drain depth limit ({}) reached for chat {}, dropping remaining", maxDrainDepth, chatId);
                    break;
                }
            }
        }
    }

    private String resolveModelUsed(BotSessionEntity session, AgentBackendClient.ChatResult result) {
        if (result != null && result.modelUsed() != null && !result.modelUsed().isBlank()) {
            return result.modelUsed();
        }
        if (session.getModelOverride() != null && !session.getModelOverride().isBlank()) {
            return session.getModelOverride();
        }
        return properties.getDefaultModel();
    }

    // ─── Streaming ────────────────────────────────────────────────

    /**
     * Stream chat from the backend, editing the message in-place via StreamEditor.
     * Falls back to synchronous chat() if streaming fails to start.
     * Checks for interrupts during streaming.
     *
     * @param chatId      the Telegram chat ID
     * @param messageText the user's message
     * @param sessionId   the session UUID string (may be null)
     * @param session     the bot session (for footer model resolution)
     * @param userMessageId the user's message ID (for reply)
     * @return the full accumulated response content and metadata
     */
    private AgentBackendClient.ChatResult streamChat(long chatId, String messageText, String sessionId,
                                                      BotSessionEntity session, long userMessageId) {
        // P0: PII Redaction — prepend redacted session context to the message
        String fullMessage = buildMessageWithContext(messageText, session, chatId);
        StringBuilder accumulated = new StringBuilder();      // clean LLM text only
        StringBuilder toolProgress = new StringBuilder();     // transient tool progress (🔧/✅ lines)
        final long[] messageId = {-1};
        final boolean[] finalized = {false};
        final boolean[] interrupted = {false};

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
                    if (messageId[0] >= 0) {
                        streamEditor.editStream(chatId, messageId[0], accumulated.toString());
                    }
                },
                // toolCallConsumer — called when backend emits tool_calls event
                toolCall -> {
                    if (messageId[0] >= 0) {
                        // Show tool progress in the streaming message (transient)
                        String toolLine = "\n\n🔧 " + toolCall + "...";
                        toolProgress.append(toolLine);
                        streamEditor.editStream(chatId, messageId[0], accumulated.toString() + toolProgress);
                    }
                },
                // toolResultConsumer — called when backend emits tool_result event
                (toolName, toolResultPreview) -> {
                    if (messageId[0] >= 0) {
                        // Show tool result in the streaming message (transient)
                        String resultLine = "\n✅ " + toolName + ": " + truncatePreview(toolResultPreview, 200);
                        toolProgress.append(resultLine);
                        streamEditor.editStream(chatId, messageId[0], accumulated.toString() + toolProgress);
                    }
                },
                // onComplete
                result -> {
                    if (messageId[0] >= 0 && accumulated.length() > 0) {
                        // Append footer to the streaming message before finalizing
                        String footer = runtimeFooter.format(
                            resolveModelUsed(session, result),
                            result.contextTokens() != null ? result.contextTokens() : 0,
                            result.contextLength() != null ? result.contextLength() : 0,
                            properties.getWorkingDirectory()
                        );
                        String finalText = accumulated.toString();
                        if (!footer.isEmpty()) {
                            finalText = finalText + footer;
                        }
                        streamEditor.finalizeStream(chatId, messageId[0], finalText);
                        finalized[0] = true;
                    }
                },
                // onError
                error -> {
                    if (error instanceof StreamInterruptedException) {
                        // Interrupted — finalize with what we have
                        interrupted[0] = true;
                        if (messageId[0] >= 0 && accumulated.length() > 0) {
                            streamEditor.finalizeStream(chatId, messageId[0],
                                accumulated + "\n\n[Interrupted by user]");
                            finalized[0] = true;
                        }
                    } else {
                        log.error("Stream error for chat {}: {}", chatId, error.getMessage());
                        // Finalize with partial content + error
                        if (messageId[0] >= 0) {
                            String errorText = accumulated.length() > 0
                                ? accumulated + "\n\n[Error: " + error.getMessage() + "]"
                                : "[Error: " + error.getMessage() + "]";
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
                    streamResult.memoryUpdated()
                );
            }
            // If streaming produced no visible tokens but has metadata, prefer the sync fallback to get content
            if (streamResult.modelUsed() != null || streamResult.contextTokens() != null) {
                return fallbackSyncWithMetadata(fullMessage, sessionId, session, streamResult);
            }
            // Stream finished but produced no content and no metadata
            return new AgentBackendClient.ChatResult(accumulated.toString(),
                streamResult.modelUsed(), streamResult.contextTokens(), streamResult.contextLength(), false,
                streamResult.memoryUpdated());
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

    private String truncatePreview(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private AgentBackendClient.ChatResult fallbackSyncWithMetadata(String messageText, String sessionId,
                                                                   com.azhukov.agent.bot.session.BotSessionEntity session,
                                                                   AgentBackendClient.ChatResult streamResult) {
        AgentBackendClient.ChatResult syncResult = backendClient.chat(messageText, sessionId, session);
        return new AgentBackendClient.ChatResult(
            syncResult.content(),
            syncResult.modelUsed() != null ? syncResult.modelUsed() : streamResult.modelUsed(),
            syncResult.contextTokens() != null ? syncResult.contextTokens() : streamResult.contextTokens(),
            syncResult.contextLength() != null ? syncResult.contextLength() : streamResult.contextLength(),
            false,
            syncResult.memoryUpdated() || streamResult.memoryUpdated()
        );
    }

    /** Internal exception to break out of streaming on interrupt. */
    private static class StreamInterruptedException extends RuntimeException {
        StreamInterruptedException() { super("Stream interrupted"); }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private BotSessionEntity resolveSession(UpdateEvent event) {
        String userId = String.valueOf(event.userId());
        String chatId = String.valueOf(event.chatId());
        String username = event.username();
        return sessionStore.resolveOrCreate(userId, chatId, username);
    }

    /**
     * P0: PII Redaction — Build the message with a redacted session context prefix.
     *
     * <p>When {@code redactPii} is enabled, prepends a session context prompt
     * where user IDs and chat IDs are hashed via {@link PiiRedactor}.
     * When disabled, the original message text is returned unchanged.
     *
     * @param messageText the user's message
     * @param session     the bot session
     * @param chatId      the chat ID
     * @return the full message (context prefix + user message)
     */
    private String buildMessageWithContext(String messageText, BotSessionEntity session, long chatId) {
        if (!properties.isRedactPii()) {
            return messageText;
        }
        String userId = session.getUserId();
        String chatIdStr = String.valueOf(chatId);
        String username = session.getUsername();
        // Determine chat type — default to "dm" for private chats
        String chatType = "dm";
        String contextPrompt = PiiRedactor.buildRedactedContextPrompt(
            "telegram", userId, chatIdStr, username, chatType, null);
        return contextPrompt + "\n\n" + messageText;
    }

    private String extractMessageText(UpdateEvent event) {
        if (event.text() != null && !event.text().isBlank()) {
            // If there's text along with media (caption is handled below), use text
            if (event.fileId() != null) {
                // Text + media: enrich with media description
                Optional<String> mediaDesc = inboundMediaHandler.handle(event);
                if (mediaDesc.isPresent()) {
                    return event.text() + "\n" + mediaDesc.get();
                }
            }
            return event.text();
        }
        if (event.caption() != null && !event.caption().isBlank()) {
            // Caption with media: enrich with media description from InboundMediaHandler
            Optional<String> mediaDesc = inboundMediaHandler.handle(event);
            if (mediaDesc.isPresent()) {
                return event.caption() + "\n" + mediaDesc.get();
            }
            return event.caption();
        }
        // For media without caption/text, use InboundMediaHandler to get a description
        if (event.fileId() != null) {
            Optional<String> mediaDesc = inboundMediaHandler.handle(event);
            if (mediaDesc.isPresent()) {
                return mediaDesc.get();
            }
            // Fallback to placeholder if handler returns empty
            return "[Media attachment: " + (event.fileType() != null ? event.fileType() : "unknown") + "]";
        }
        return null;
    }

    private void sendFormatted(long chatId, String text) {
        sendFormatted(chatId, text, 0L);
    }

    /**
     * Send formatted text, optionally with reply_to_message_id for thread reply mode (B1.6).
     *
     * @param chatId         target chat ID
     * @param text           text to send
     * @param userMessageId  the user's message ID for reply_to (0 = no reply)
     */
    private void sendFormatted(long chatId, String text, long userMessageId) {
        // B2.8: Response filter — filter silent/empty responses
        if (responseFilter.shouldFilter(text)) {
            log.debug("Response filtered (silent or empty) for chat {}", chatId);
            return;
        }

        // Check for MEDIA: outbound — send as photo instead of text
        java.util.regex.Matcher mediaMatcher = MEDIA_PATTERN.matcher(text);
        if (mediaMatcher.find()) {
            String mediaPath = mediaMatcher.group(1);
            // Remove the MEDIA: line from the text
            String remainingText = MEDIA_PATTERN.matcher(text).replaceAll("").trim();
            sendMediaFile(chatId, mediaPath, remainingText);
        }

        // If there's remaining text after removing MEDIA: lines, send it
        String textWithoutMedia = MEDIA_PATTERN.matcher(text).replaceAll("").trim();
        if (textWithoutMedia.isBlank()) {
            return;
        }

        String parseMode = properties.getParseMode();
        String formatted = textWithoutMedia;
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            formatted = MarkdownConverter.convert(textWithoutMedia);
        }

        // B1.6: Thread reply mode — off/all/first
        String replyMode = properties.getReplyToMode();
        Long replyToMessageId = null; // null = no reply_to by default

        List<String> chunks = MessageSplitter.split(formatted);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (!chunk.isBlank()) {
                if ("all".equalsIgnoreCase(replyMode) && userMessageId > 0) {
                    replyToMessageId = userMessageId;
                } else if ("first".equalsIgnoreCase(replyMode) && i == 0 && userMessageId > 0) {
                    replyToMessageId = userMessageId;
                } else {
                    replyToMessageId = null;
                }
                telegramClient.sendMessage(chatId, chunk, parseMode, replyToMessageId, null);
            }
        }
    }

    /**
     * Sends a file from the filesystem as a photo via Telegram.
     *
     * @param chatId  the target chat ID
     * @param path    the file path to send
     * @param caption optional caption text
     */
    private void sendMediaFile(long chatId, String path, String caption) {
        try {
            File file = new File(path);
            if (!file.exists() || !file.isFile()) {
                log.warn("MEDIA file not found: {}", path);
                telegramClient.sendMessage(chatId, "Media file not found: " + path);
                return;
            }
            byte[] data = Files.readAllBytes(file.toPath());
            String parseMode = properties.getParseMode();
            String formattedCaption = null;
            if (caption != null && !caption.isBlank()) {
                formattedCaption = caption;
                if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
                    formattedCaption = MarkdownConverter.convert(caption);
                }
            }
            telegramClient.sendPhoto(chatId, data, formattedCaption, parseMode);
            log.debug("Sent media file {} to chat {}", path, chatId);
        } catch (Exception e) {
            log.error("Failed to send media file {} to chat {}: {}", path, chatId, e.getMessage());
            telegramClient.sendMessage(chatId, "Failed to send media: " + e.getMessage());
        }
    }

    private void sendError(long chatId, String message) {
        try {
            String parseMode = properties.getParseMode();
            String text = message;
            if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
                text = MarkdownConverter.convert(message);
            }
            telegramClient.sendMessage(chatId, text, parseMode, null, null);
        } catch (Exception e) {
            log.error("Failed to send error message to chat {}: {}", chatId, e.getMessage());
        }
    }

    /** Pattern to match MEDIA:/path in response text. */
    private static final java.util.regex.Pattern MEDIA_PATTERN =
        java.util.regex.Pattern.compile("MEDIA:(\\S+)");


    @PostConstruct
    void init() {
        textBatchDebouncer.onDispatch(this::dispatchTextBatch);
        photoBatchDebouncer.onDispatch(this::dispatchPhotoBatch);
    }
    /**
     * Synthesize TTS audio for the response text and send as a voice message.
     */
    private void sendVoiceResponse(long chatId, String text) {
        try {
            // Strip MEDIA: lines and markdown from text before TTS
            String cleanText = MEDIA_PATTERN.matcher(text).replaceAll("").trim();
            if (cleanText.isBlank()) return;
            // Limit text length for TTS (Telegram voice messages max ~1 hour, but keep it reasonable)
            if (cleanText.length() > 4000) {
                cleanText = cleanText.substring(0, 4000);
            }
            byte[] audio = backendClient.tts(cleanText, null);
            if (audio != null && audio.length > 0) {
                telegramClient.sendVoice(chatId, audio, null);
                log.debug("Sent voice response to chat {} ({} bytes)", chatId, audio.length);
            }
        } catch (Exception e) {
            log.warn("TTS voice response failed for chat {}: {}", chatId, e.getMessage());
        }
    }
}