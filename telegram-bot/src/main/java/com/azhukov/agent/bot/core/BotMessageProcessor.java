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
import com.azhukov.agent.bot.core.RuntimeFooter;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.azhukov.agent.bot.formatting.ResponseFilter;
import com.azhukov.agent.bot.goal.GoalAutoContinueService;
import com.azhukov.agent.bot.group.GroupMessageFilter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.core.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.session.EditCaptureService;
import com.azhukov.agent.bot.session.PiiRedactor;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

/**
 * Main message processor — the thin orchestrator for all incoming {@link UpdateEvent}s.
 *
 * <p>c5: Event-type routing is delegated to {@link UpdateDispatcher} and the
 * streaming chat lifecycle is delegated to {@link StreamingOrchestrator}. This
 * class now owns only the per-chat locking, the per-event business logic
 * (authorization, busy handling, footer, reactions, goal auto-continue) and the
 * message formatting/delivery helpers.
 *
 * <p>Flow (delegated to {@link UpdateDispatcher#dispatch}):
 * <ol>
 * <li>If callback_query → route to {@link CallbackQueryHandler}</li>
 * <li>If command → route to {@link CommandRegistry}, send response via {@link TelegramClient}</li>
 * <li>If text/media → check authorization, resolve session, check busy state,
 * start typing, stream via {@link StreamingOrchestrator} + {@link StreamEditor},
 * stop typing, format via {@link MarkdownConverter} + {@link MessageSplitter}, send</li>
 * <li>On error → send error message to chat</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BotMessageProcessor implements Consumer<UpdateEvent>, UpdateDispatcher.Handlers, StreamingOrchestrator.ProcessorHooks {

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
    private final MediaDeliveryService mediaDeliveryService;
    private final RuntimeFooter runtimeFooter;
    private final ReactionManager reactionManager;
    private final TextBatchDebouncer textBatchDebouncer;
    private final PhotoBatchDebouncer photoBatchDebouncer;
    private final GroupMessageFilter groupMessageFilter;
    private final SlashAccessPolicy slashAccessPolicy;
    private final ResponseFilter responseFilter;
    private final GoalAutoContinueService goalAutoContinueService;
    private final EditCaptureService editCaptureService;
    // c5: extracted collaborators
    private final UpdateDispatcher updateDispatcher;
    private final StreamingOrchestrator streamingOrchestrator;

    /**
     * Per-chat locks to prevent concurrent processing of messages within the same chat.
     * Keyed by chatId. Used in {@link #handleTextOrMedia} to serialize work per chat.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

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
        // c5: delegate routing to UpdateDispatcher, passing ourselves as the handler.
        updateDispatcher.dispatch(event, this);
    }

    // ─── UpdateDispatcher.Handlers ────────────────────────────────

    @Override
    public void handleCallbackQuery(UpdateEvent event) {
        String response = callbackQueryHandler.handle(event);
        if (response != null && !response.isBlank()) {
            telegramClient.sendMessage(event.chatId(), response);
        }
    }

    @Override
    public void handleCommand(UpdateEvent event) {
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

    /**
     * Handle an edited message from Telegram. Telegram sends {@code edited_message}
     * updates whenever a user edits a previously-sent message.
     *
     * <p>To avoid regenerating responses for every edit (which would be expensive
     * and surprising), we simply acknowledge the edit and tell the user to use
     * {@code /retry} if they want a fresh response.
     *
     * <p>The message is routed to the correct forum thread via
     * {@code message_thread_id} when present.
     */
    @Override
    public void handleEditedMessage(UpdateEvent event) {
        long chatId = event.chatId();
        Integer threadId = event.messageThreadId() > 0 ? (int) event.messageThreadId() : null;
        log.debug("Edited message from chat {} (updateId={}) — acknowledging, not regenerating",
            chatId, event.updateId());
        if (threadId != null) {
            telegramClient.sendMessage(chatId,
                "Message edited — use /retry to regenerate",
                properties.getParseMode(), null, threadId, false);
        } else {
            telegramClient.sendMessage(chatId,
                "Message edited — use /retry to regenerate",
                properties.getParseMode(), null, null);
        }
    }

    /**
     * P35: Handle a text message when the chat is in edit-capture mode.
     * <p>
     * The message text is treated as edited content for the pending approval.
     * The capture is ended (consumed) and a confirmation is sent to the user.
     *
     * @param event the inbound text event
     */
    @Override
    public void handleEditCapture(UpdateEvent event) {
        long chatId = event.chatId();
        EditCaptureService.CaptureContext ctx = editCaptureService.getCapture(chatId);
        if (ctx == null) return;

        String text = event.text();
        editCaptureService.endCapture(chatId);
        log.info("Edit-capture for chat {}: approvalId={}, textLen={}",
            chatId, ctx.approvalId(), text != null ? text.length() : 0);
        telegramClient.sendMessage(chatId,
            "✅ Edited content captured for approval #" + ctx.approvalId());
    }

    @Override
    public void sendError(long chatId, String message) {
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

    // ─── Text / Media ──────────────────────────────────────────────

    @Override
    public void handleTextOrMedia(UpdateEvent event) {
        long chatId = event.chatId();
        ReentrantLock lock = locks.computeIfAbsent(chatId, k -> new ReentrantLock());
        lock.lock();
        try {
            // m24: the lock is already held here — call the body directly.
            // The old handleTextOrMediaInternal re-acquired the lock (dead code,
            // harmless under ReentrantLock but wasteful and misleading); it has
            // been removed and the body inlined below.
            handleTextOrMediaInternalBody(event);
            // M28: Drain queued messages inside the per-chat lock to prevent
            // new messages arriving between draining and processing
            drainQueueLocked(chatId);
        } finally {
            lock.unlock();
        }
    }

    private void handleTextOrMediaInternalBody(UpdateEvent event) {
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
            String effectiveMode = busyHandler.getEffectiveBusyInputMode();
            handleBusyMessage(chatId, event, effectiveMode, session);
            return;
        }

        // B1.2: Reaction — processing start
        reactionManager.onProcessingStart(chatId, event.messageId());

        // Process the turn
        busyHandler.markBusy(chatId);
        // B1.6/B2.7: Route typing indicator to the correct forum thread
        Integer typingThreadId = event.messageThreadId() > 0 ? (int) event.messageThreadId() : null;
        typingManager.startTyping(chatId, typingThreadId);

        AgentBackendClient.ChatResult result;
        try {
            String sessionId = session.getBackendSessionId() != null
                ? session.getBackendSessionId().toString()
                : null;

            // B1.6/B2.7: Thread the message_thread_id from the event through to all sends
            long threadId = event.messageThreadId();

            // Build footer text (will be appended to streaming message or sync response)
            result = streamingOrchestrator.streamChat(chatId, messageText, sessionId, session,
                event.messageId(), threadId, this);

            // Persist the backend-assigned session ID for conversation history continuity
            if (result.backendSessionId() != null) {
                sessionStore.updateBackendSessionId(session.getId(), result.backendSessionId());
            }

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
                sendFormatted(chatId, backendResponse, event.messageId(), threadId);
            }

            // Voice mode: synthesize TTS and send as voice message
            if (session.isVoiceMode() && result.content() != null && !result.content().isBlank()) {
                sendVoiceResponse(chatId, result.content());
            }

            // 💾 Memory updated notification 
            if (result.memoryUpdated()) {
                sendFormatted(chatId, "💾 Self-improvement review: Memory updated", event.messageId(), threadId);
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
                        sendFormatted(chatId, continuation, event.messageId(), threadId);
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
     * Handle a message that arrives while the agent is busy.
     * Dispatches based on the effective busy-input mode:
     * <ul>
     *   <li><b>steer</b>: call the backend steer API to inject mid-run, send busy-ack</li>
     *   <li><b>queue</b>: buffer the message for the next turn, send busy-ack</li>
     *   <li><b>interrupt</b>: stop the current run, queue the message, send busy-ack</li>
     * </ul>
     * For "interrupt" mode with active subagents, demotes to "queue" to avoid
     * destroying subagent work (mirrors Hermes #30170).
     *
     * @param chatId the Telegram chat ID
     * @param event the inbound update event
     * @param effectiveMode the resolved busy-input mode ("steer", "queue", or "interrupt")
     * @param session the bot session (for session ID lookup)
     */
    private void handleBusyMessage(long chatId, UpdateEvent event, String effectiveMode,
                                    BotSessionEntity session) {
        String messageText = extractMessageText(event);
        if (messageText == null || messageText.isBlank()) {
            log.debug("No text in busy message for chat {}, skipping", chatId);
            return;
        }

        boolean demotedForSubagents = false;
        String actualMode = effectiveMode;

        // #30170: Demote interrupt to queue when subagents are active
        if ("interrupt".equals(effectiveMode)) {
            // Check if the backend has active subagents — if so, demote to queue
            // to avoid destroying subagent work (mirrors Hermes behavior).
            // The backend's DelegateTaskTool tracks active subagents per session.
            // We check via the backend client's health/active-agents endpoint.
            // For simplicity and safety, we always demote if we can't confirm
            // no subagents are active — this matches Hermes' conservative approach.
            try {
                if (session.getBackendSessionId() != null && backendHasActiveSubagents(session)) {
                    demotedForSubagents = true;
                    actualMode = "queue";
                    log.info("Demoting interrupt to queue for chat {} — active subagents detected", chatId);
                }
            } catch (Exception e) {
                log.debug("Could not check subagent status for chat {}: {}", chatId, e.getMessage());
            }
        }

        boolean steered = false;

        if ("steer".equals(actualMode)) {
            // Steer mode: inject mid-run via the backend steer API
            String sessionId = session.getBackendSessionId() != null
                ? session.getBackendSessionId().toString()
                : null;
            if (sessionId != null) {
                try {
                    steered = backendClient.steer(sessionId, messageText);
                } catch (Exception e) {
                    log.warn("Steer failed for chat {}: {}", chatId, e.getMessage());
                    steered = false;
                }
            }
            if (!steered) {
                // Fall back to queue if steer failed
                actualMode = "queue";
            }
        }

        // Queue the message unless it was successfully steered
        // (steered text already landed inside the run — don't replay as next-turn message)
        if (!steered) {
            busyHandler.queueMessage(chatId, event);
        }

        // Interrupt if in interrupt mode (and not demoted)
        if ("interrupt".equals(actualMode) && !demotedForSubagents) {
            busyHandler.interrupt(chatId);
            log.debug("Interrupted busy chat {} and queued interrupting message", chatId);
        } else if (steered) {
            log.debug("Steered message into active run for chat {}", chatId);
        } else {
            log.debug("Queued message for busy chat {} (mode={})", chatId, actualMode);
        }

        // Send busy-ack if enabled and debounce allows
        sendBusyAck(chatId, event, actualMode, steered, demotedForSubagents);
    }

    /**
     * Send a busy-ack message to the user, respecting debounce and config.
     *
     * @param chatId the Telegram chat ID
     * @param event the inbound update event (for thread routing)
     * @param mode the effective mode ("steer", "queue", or "interrupt")
     * @param steered whether the message was successfully steered
     * @param demotedForSubagents whether interrupt was demoted to queue for subagent protection
     */
    private void sendBusyAck(long chatId, UpdateEvent event, String mode,
                             boolean steered, boolean demotedForSubagents) {
        if (!busyHandler.isBusyAckEnabled()) {
            log.debug("Busy-ack suppressed for chat {} (disabled)", chatId);
            return;
        }
        if (!busyHandler.shouldSendBusyAck(chatId)) {
            log.debug("Busy-ack debounced for chat {}", chatId);
            return;
        }

        String ackMessage;
        if (steered) {
            ackMessage = "⏩ Steered into current run. Your message arrives after the next tool call.";
        } else if ("queue".equals(mode) && demotedForSubagents) {
            ackMessage = "⏳ Subagent working — your message is queued for when it finishes (use /stop to cancel everything).";
        } else if ("queue".equals(mode)) {
            ackMessage = "⏳ Queued for the next turn. I'll respond once the current task finishes.";
        } else {
            ackMessage = "⚡ Interrupting current task. I'll respond to your message shortly.";
        }

        // First-time onboarding hint
        if (busyHandler.shouldShowOnboardingHint()) {
            ackMessage = ackMessage + "\n\n💡 You can change how messages are handled while I'm busy. "
                + "Current mode: " + mode + ". "
                + "Use /steer to inject a note mid-run, or ask the admin about busy-input-mode settings.";
        }

        Integer threadId = event.messageThreadId() > 0 ? (int) event.messageThreadId() : null;
        telegramClient.sendMessage(chatId, ackMessage,
            properties.getParseMode(), null, threadId, false);
        log.debug("Sent busy-ack to chat {} (mode={})", chatId, mode);
    }

    /**
     * Check if the backend has active subagents for the given session.
     * Uses the backend's active-agents endpoint.
     *
     * @param session the bot session
     * @return true if there are active subagents
     */
    private boolean backendHasActiveSubagents(BotSessionEntity session) {
        if (session.getBackendSessionId() == null) {
            return false;
        }
        try {
            var agents = backendClient.listActiveAgents();
            if (agents == null || !agents.isArray()) {
                return false;
            }
            // If any active agent has a different session ID, it's a subagent
            String currentSession = session.getBackendSessionId().toString();
            for (var agent : agents) {
                var id = agent.path("sessionId").asText("");
                if (!id.isEmpty() && !id.equals(currentSession)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Could not check active subagents: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Drain queued messages for a chat — linear, not recursive.
     * M28: Must be called while holding the per-chat lock to prevent
     * new messages from arriving between draining and processing.
     * Uses {@link #handleTextOrMediaInternalBody} directly to avoid
     * re-acquiring the lock.
     * Guards against infinite loops with a max drain depth.
     */
    private void drainQueueLocked(long chatId) {
        int maxDrainDepth = 100;
        int drained = 0;
        while (busyHandler.hasQueued(chatId) && drained < maxDrainDepth) {
            List<UpdateEvent> queued = busyHandler.drainQueue(chatId);
            for (UpdateEvent queuedEvent : queued) {
                try {
                    handleTextOrMediaInternalBody(queuedEvent);
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

    // ─── StreamingOrchestrator.ProcessorHooks ─────────────────────

    @Override
    public String resolveModelUsed(BotSessionEntity session, AgentBackendClient.ChatResult result) {
        if (result != null && result.modelUsed() != null && !result.modelUsed().isBlank()) {
            return result.modelUsed();
        }
        if (session.getModelOverride() != null && !session.getModelOverride().isBlank()) {
            return session.getModelOverride();
        }
        return properties.getDefaultModel();
    }

    @Override
    public void deliverMedia(long chatId, List<MediaDeliveryService.MediaDescriptor> media, Integer threadId) {
        if (media == null || media.isEmpty()) return;

        // Partition: images (not as_document) go to batch, everything else goes individually
        List<MediaDeliveryService.MediaDescriptor> imageBatch = new ArrayList<>();
        List<MediaDeliveryService.MediaDescriptor> individualFiles = new ArrayList<>();

        for (MediaDeliveryService.MediaDescriptor desc : media) {
            // Security: validate path against allowed base directories
            if (!isMediaPathAllowed(desc.path())) {
                log.warn("MEDIA path outside allowed directories, skipping: {}", desc.path());
                continue;
            }
            if (desc.isImage() && !desc.asDocument()) {
                imageBatch.add(desc);
            } else {
                individualFiles.add(desc);
            }
        }

        // Deliver image batch via sendMediaGroup (up to 10 per call)
        if (!imageBatch.isEmpty()) {
            deliverImageBatch(chatId, imageBatch);
        }

        // Deliver individual files
        for (MediaDeliveryService.MediaDescriptor desc : individualFiles) {
            deliverSingleMedia(chatId, desc);
        }
    }

    @Override
    public String buildMessageWithContext(String messageText, BotSessionEntity session, long chatId) {
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

    // ─── Helpers ───────────────────────────────────────────────────

    private BotSessionEntity resolveSession(UpdateEvent event) {
        String userId = String.valueOf(event.userId());
        String chatId = String.valueOf(event.chatId());
        String username = event.username();
        BotSessionEntity session = sessionStore.resolveOrCreate(userId, chatId, username);
        // Store firstName and languageCode as session metadata so buildChatBody
        // can forward them to the backend for the system prompt volatile tier.
        if (event.firstName() != null && !event.firstName().isBlank()) {
            session.setMetadata("firstName", event.firstName());
        }
        if (event.languageCode() != null && !event.languageCode().isBlank()) {
            session.setMetadata("languageCode", event.languageCode());
        }
        // Store chatType for backend context (dm/group/channel/supergroup)
        session.setMetadata("chatType", determineChatType(event));
        return session;
    }

    /** Determine chat type string from UpdateEvent for session metadata. */
    private String determineChatType(UpdateEvent event) {
        if (event.type() == UpdateEvent.Type.CHANNEL_POST) return "channel";
        if (event.chatId() < 0) return "group"; // Telegram: negative chatId = group/supergroup
        return "dm";
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
        sendFormatted(chatId, text, 0L, 0L);
    }

    /**
     * Send formatted text, optionally with reply_to_message_id for thread reply mode (B1.6).
     * Defaults messageThreadId to 0 (no thread).
     *
     * @param chatId target chat ID
     * @param text text to send
     * @param userMessageId the user's message ID for reply_to (0 = no reply)
     */
    private void sendFormatted(long chatId, String text, long userMessageId) {
        sendFormatted(chatId, text, userMessageId, 0L);
    }

    /**
     * Send formatted text, optionally with reply_to_message_id for thread reply mode (B1.6),
     * and routed to a specific forum thread via message_thread_id.
     *
     * @param chatId target chat ID
     * @param text text to send
     * @param userMessageId the user's message ID for reply_to (0 = no reply)
     * @param messageThreadId the Telegram forum thread ID (0 = no thread routing)
     */
    private void sendFormatted(long chatId, String text, long userMessageId, long messageThreadId) {
        // B2.8: Response filter — filter silent/empty responses
        if (responseFilter.shouldFilter(text)) {
            log.debug("Response filtered (silent or empty) for chat {}", chatId);
            return;
        }

        Integer threadId = messageThreadId > 0 ? (int) messageThreadId : null;

        // S-2: Extract MEDIA: tags and bare file paths using MediaDeliveryService
        String textForDisplay = text;
        if (properties.isMediaDeliveryEnabled()) {
            MediaDeliveryService.ExtractionResult extraction = mediaDeliveryService.extractMediaTags(text);
            textForDisplay = extraction.cleanedText();
            // Deliver extracted media files as native Telegram attachments
            if (!extraction.media().isEmpty()) {
                deliverMedia(chatId, extraction.media(), threadId);
            }
        }

        if (textForDisplay.isBlank()) {
            return;
        }

        String parseMode = properties.getParseMode();
        String formatted = textForDisplay;
        // Audit M21: strip think blocks from non-streaming (fallback) path.
        // StreamEditor strips them during streaming, but sendFormatted is used
        // when streaming didn't finalize — without this, raw think tags reach the user.
        formatted = StreamEditor.stripThinkTagsRegex(formatted);
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            formatted = MarkdownConverter.convert(formatted);
        } else if ("HTML".equalsIgnoreCase(parseMode)) {
            // Audit M20: escape HTML-special characters in LLM output to prevent
            // injection of <a>, <b>, <code> and other Telegram-allowed tags.
            formatted = formatted.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
                // Use 6-arg sendMessage when thread routing is needed, 5-arg otherwise
                if (threadId != null) {
                    telegramClient.sendMessage(chatId, chunk, parseMode, replyToMessageId, threadId, false);
                } else {
                    telegramClient.sendMessage(chatId, chunk, parseMode, replyToMessageId, null);
                }
            }
        }
    }

    /**
     * S-2: Validate that a media file path is within allowed base directories.
     * Security check to prevent path-traversal attacks.
     *
     * @param path the file path to validate
     * @return true if the path is within an allowed directory
     */
    private boolean isMediaPathAllowed(String path) {
        try {
            Path resolved = Paths.get(path).normalize();
            Path workingDir = Paths.get(properties.getWorkingDirectory()).normalize();
            Path mediaDir = Paths.get("/tmp/agent-media/").normalize();
            return resolved.startsWith(workingDir) || resolved.startsWith(mediaDir);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deliver a batch of images via sendMediaGroup (up to 10 per call).
     * For a single image, uses sendPhoto instead.
     */
    private void deliverImageBatch(long chatId, List<MediaDeliveryService.MediaDescriptor> images) {
        if (images.size() == 1) {
            // Single image — use sendPhoto
            MediaDeliveryService.MediaDescriptor desc = images.get(0);
            try {
                File file = new File(desc.path());
                if (!file.exists() || !file.isFile()) {
                    log.warn("Media file not found: {}", desc.path());
                    // M2: Notify user that the referenced file doesn't exist
                    telegramClient.sendMessage(chatId, "⚠️ Media file not found: " + desc.path());
                    return;
                }
                byte[] data = Files.readAllBytes(file.toPath());
                String fileName = file.getName();
                telegramClient.sendPhoto(chatId, data, null, null);
                log.debug("Sent photo {} to chat {}", desc.path(), chatId);
            } catch (Exception e) {
                log.error("Failed to send photo {} to chat {}: {}", desc.path(), chatId, e.getMessage());
            }
            return;
        }

        // Multiple images — chunk into groups of 10
        for (int i = 0; i < images.size(); i += MediaDeliveryService.MAX_MEDIA_GROUP_SIZE) {
            int end = Math.min(i + MediaDeliveryService.MAX_MEDIA_GROUP_SIZE, images.size());
            List<MediaDeliveryService.MediaDescriptor> chunk = images.subList(i, end);

            List<TelegramClient.PhotoInput> photoInputs = new ArrayList<>();
            for (MediaDeliveryService.MediaDescriptor desc : chunk) {
                try {
                    File file = new File(desc.path());
                    if (!file.exists() || !file.isFile()) {
                        log.warn("Media file not found, skipping: {}", desc.path());
                        // M2: Notify user that the referenced file doesn't exist
                        telegramClient.sendMessage(chatId, "⚠️ Media file not found: " + desc.path());
                        continue;
                    }
                    byte[] data = Files.readAllBytes(file.toPath());
                    String fileName = file.getName();
                    // First photo in the group gets the caption (if any)
                    String caption = (photoInputs.isEmpty()) ? null : null;
                    photoInputs.add(new TelegramClient.PhotoInput(data, fileName, caption));
                } catch (Exception e) {
                    log.warn("Failed to read media file {}: {}", desc.path(), e.getMessage());
                }
            }

            if (!photoInputs.isEmpty()) {
                List<Long> messageIds = telegramClient.sendMediaGroup(chatId, photoInputs);
                log.debug("Sent media group of {} to chat {} (messageIds: {})",
                    photoInputs.size(), chatId, messageIds);
            }
        }
    }

    /**
     * Deliver a single media file, routing by extension.
     * - Video → sendVideo
     * - Audio with [[audio_as_voice]] → sendAudioAsVoice
     * - Image with [[as_document]] → sendDocument
     * - Everything else → sendDocument
     */
    private void deliverSingleMedia(long chatId, MediaDeliveryService.MediaDescriptor desc) {
        try {
            File file = new File(desc.path());
            if (!file.exists() || !file.isFile()) {
                log.warn("Media file not found: {}", desc.path());
                // M2: Notify user that the referenced file doesn't exist
                telegramClient.sendMessage(chatId, "⚠️ Media file not found: " + desc.path());
                return;
            }
            byte[] data = Files.readAllBytes(file.toPath());
            String fileName = file.getName();
            String parseMode = properties.getParseMode();

            if (desc.isVideo()) {
                telegramClient.sendVideo(chatId, data, fileName, null, null);
                log.debug("Sent video {} to chat {}", desc.path(), chatId);
            } else if (desc.isAudio() && desc.asVoice()) {
                telegramClient.sendAudioAsVoice(chatId, data, fileName, null);
                log.debug("Sent voice {} to chat {}", desc.path(), chatId);
            } else {
                // Document (covers as_document images, audio without voice, PDFs, etc.)
                telegramClient.sendDocument(chatId, data, fileName, null, null);
                log.debug("Sent document {} to chat {}", desc.path(), chatId);
            }
        } catch (Exception e) {
            log.error("Failed to send media {} to chat {}: {}", desc.path(), chatId, e.getMessage());
        }
    }

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
            // Strip MEDIA: lines and directives from text before TTS
            String cleanText = mediaDeliveryService.stripMediaTagsForDisplay(text);
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