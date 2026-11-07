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
import com.azhukov.agent.bot.keyboard.ClarifyTextInterceptor;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.core.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.session.EditCaptureService;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private ClarifyTextInterceptor clarifyTextInterceptor;

    @org.springframework.beans.factory.annotation.Autowired
    void setClarifyTextInterceptor(ClarifyTextInterceptor clarifyTextInterceptor) {
        this.clarifyTextInterceptor = clarifyTextInterceptor;
    }

    private final BotProperties properties;
    private final StreamEditor streamEditor;
    private final InboundMediaHandler inboundMediaHandler;
    private final MediaDeliveryService mediaDeliveryService;
    private final AttachmentApiClient attachmentApiClient;
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
        String callbackData = event.callbackData();
        String response = callbackQueryHandler.handle(event);
        if (response != null && !response.isBlank()) {
            telegramClient.sendMessage(event.chatId(), response);
        }
    }

    private void handleClarificationAnswer(UpdateEvent callback, String answer) {
        if (answer == null || answer.isBlank()) return;
        UpdateEvent reply = new UpdateEvent(callback.updateId(), UpdateEvent.Type.TEXT,
            callback.chatId(), callback.userId(), callback.username(), callback.firstName(),
            callback.languageCode(), answer, null, null, null, null, null, null,
            false, null, null, callback.messageId(), null, callback.messageThreadId(), null);
        handleTextOrMedia(reply);
    }

    @Override
    public void handleCommand(UpdateEvent event) {
        // /stop and /steer are control-plane input: they must not wait behind
        // the active turn. Waiting makes /steer arrive only after the turn it
        // was meant to influence has already completed.
        if ("stop".equals(event.commandName()) || "steer".equals(event.commandName())) {
            handleControlCommandImmediately(event);
            return;
        }
        // M26 fix: commands mutate session state (/new, /model, /checkpoint …) —
        // serialize per chat like handleTextOrMedia so a command can't interleave
        // with an in-flight turn or another command on the same chat.
        long chatId = event.chatId();
        ReentrantLock lock = locks.computeIfAbsent(chatId, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(chatId, "Interrupted while waiting to process your command. Please try again.");
            return;
        }
        if (!acquired) {
            sendError(chatId, "⏳ This chat is busy — another message is still being processed. Please try again shortly.");
            return;
        }
        try {
            handleCommandLocked(event);
        } finally {
            lock.unlock();
        }
    }

    private void handleControlCommandImmediately(UpdateEvent event) {
        String commandName = event.commandName();
        if (!slashAccessPolicy.canRun(event.userId(), commandName)) {
            telegramClient.sendMessage(event.chatId(), "⛔ You don't have access to /" + commandName);
            return;
        }
        CommandHandler handler = commandRegistry.get(commandName);
        if (handler == null) {
            sendError(event.chatId(), "Command /" + commandName + " is unavailable.");
            return;
        }
        try {
            String response = handler.handle(event, resolveSession(event));
            if (response != null && !response.isBlank()) {
                sendFormatted(event.chatId(), response);
            }
        } catch (Exception e) {
            log.error("Command /{} failed: {}", commandName, e.getMessage(), e);
            sendError(event.chatId(), "Error executing command: " + e.getMessage());
        }
    }

    private void handleCommandLocked(UpdateEvent event) {
        String commandName = event.commandName();
        CommandHandler handler = commandRegistry.get(commandName);
        if (handler == null) {
            // rev-105: Hermes parity (gateway/run.py:18055+) — unknown slash
            // commands fall through to skill commands (/skill-name loads the
            // skill's SKILL.md as a user turn). SkillCommandService was fully
            // ported but had ZERO callers — /skill:name never worked in the bot.
            if (trySkillCommandInvocation(event, commandName)) {
                return;
            }
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
    public boolean shouldBypassTextBatch(UpdateEvent event) {
        return event != null && busyHandler.isBusy(event.chatId());
    }

    @Override
    public void handleTextOrMedia(UpdateEvent event) {
        long chatId = event.chatId();
        // A replacement message must reach the busy-mode handler while the
        // active turn owns the per-chat lock; otherwise interrupt mode cannot
        // cancel anything until after that turn has already finished.
        if (busyHandler.isBusy(chatId)) {
            if (tryResolveTypedClarify(chatId, event)) {
                return;
            }
            handleBusyMessage(chatId, event, busyHandler.getEffectiveBusyInputMode(), resolveSession(event));
            return;
        }
        ReentrantLock lock = locks.computeIfAbsent(chatId, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(chatId, "Interrupted while waiting to process your message. Please try again.");
            return;
        }
        if (!acquired) {
            sendError(chatId, "⏳ This chat is busy — another message is still being processed. Please try again shortly.");
            return;
        }
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

    private boolean tryResolveTypedClarify(long chatId, UpdateEvent event) {
        if (clarifyTextInterceptor == null) {
            return false;
        }
        String messageText = extractMessageText(event);
        if (messageText == null || messageText.isBlank()) {
            return false;
        }
        String outcome = clarifyTextInterceptor.tryResolve(resolveSession(event), messageText);
        return handleTypedClarifyOutcome(chatId, outcome);
    }

    private boolean handleTypedClarifyOutcome(long chatId, String outcome) {
        if (outcome == null) {
            return false;
        }
        if ("invalid_selection".equals(outcome)) {
            sendError(chatId, "⚠️ Invalid selection — pick a listed option or type the full label.");
        } else if ("resolved".equals(outcome)) {
            typingManager.resumeTyping(chatId);
        }
        return true;
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
            telegramClient.sendMessage(chatId, "⛔ You are not authorized to use this bot.");
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
        // WP-11: collect artifact ids the media handler registered, strip the
        // inline markers from the visible text, and forward them as structured
        // attachment references on the chat request.
        java.util.List<String> artifactIds = extractArtifactIds(messageText);
        messageText = stripArtifactMarkers(messageText);

        if (busyHandler.isBusy(chatId) && clarifyTextInterceptor != null
                && messageText != null && !messageText.isBlank()) {
            String clarifyOutcome = clarifyTextInterceptor.tryResolve(session, messageText);
            if (handleTypedClarifyOutcome(chatId, clarifyOutcome)) {
                return;
            }
        }
        // Check busy state
        if (busyHandler.isBusy(chatId)) {
            String effectiveMode = busyHandler.getEffectiveBusyInputMode();
            handleBusyMessage(chatId, event, effectiveMode, session);
            return;
        }

        // The turn was already interrupted before it acquired the processing
        // slot, therefore normal execution and auto-continuation are skipped.
        if (busyHandler.isInterrupted(chatId)) {
            log.debug("Skipping already interrupted turn for chat {}", chatId);
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
        String sessionId = session.getBackendSessionId() != null
            ? session.getBackendSessionId().toString()
            : null;
        // Thread the message_thread_id through to all response sends.
        long threadId = event.messageThreadId();
        if (session != null && session.getLastMessageThreadId() != threadId) {
            session.setLastMessageThreadId(threadId);
        }
        try {
            // Build footer text (will be appended to streaming message or sync response)
            result = streamingOrchestrator.streamChat(chatId, messageText, sessionId, session,
                event.messageId(), threadId, artifactIds, this);

            // A /stop or interrupt-mode replacement can arrive while the
            // backend stream is unwinding. Do not render, persist, or continue
            // a result belonging to that cancelled turn.
            if (busyHandler.isInterrupted(chatId)) {
                log.debug("Discarding interrupted turn result for chat {}", chatId);
                reactionManager.onCancel(chatId, event.messageId());
                typingManager.stopTyping(chatId);
                return;
            }

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
                    properties.getWorkingDirectory(),
                    -1,
                    session == null || session.isFooterEnabled()
                );
                String backendResponse = streamEditor.takePendingFinalText(chatId);
                if (backendResponse == null || backendResponse.isBlank()) {
                    backendResponse = result.content();
                }
                if (footer != null && !footer.isEmpty() && !backendResponse.endsWith(footer)) {
                    backendResponse = backendResponse + footer;
                }

                typingManager.flushTyping(chatId);
                var media = mediaDeliveryService.extractMediaTags(backendResponse);
                if (media.media().isEmpty() || !properties.isMediaDeliveryEnabled()) {
                    sendFormatted(chatId, backendResponse, event.messageId(), threadId);
                } else if (media.cleanedText().isBlank() || sendFormattedWithReceipt(chatId, media.cleanedText(), event.messageId(), threadId)) {
                    deliverMedia(chatId, media.media(), threadId > 0 ? (int) threadId : null,
                        Long.toString(event.userId()), session.getBackendSessionId());
                }
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

        // Interrupt if in interrupt mode (and not demoted). The local flag only
        // stops Telegram rendering; the backend has a separate streaming turn
        // and must receive /agent/stop too. Without this call an incoming
        // message looked cancelled in the chat while the model/tool loop kept
        // running server-side until its natural completion.
        if ("interrupt".equals(actualMode) && !demotedForSubagents) {
            busyHandler.interrupt(chatId);
            String backendSessionId = session.getBackendSessionId() == null
                ? null : session.getBackendSessionId().toString();
            boolean backendStopped = backendSessionId != null && backendClient.stop(backendSessionId);
            log.debug("Interrupted busy chat {} and queued message (backendStopped={})",
                chatId, backendStopped);
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
            // An interrupt-mode message is queued while the previous turn is
            // still marked busy. Clear the per-turn marker BEFORE replaying it,
            // otherwise the replay sees itself as busy, cancels itself and is
            // queued again until the drain-depth guard intervenes.
            if (busyHandler.isInterrupted(chatId)) {
                busyHandler.markFree(chatId);
            }
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

    /**
     * rev-105: Hermes parity (gateway/run.py:18055+) — when an unknown slash
     * command arrives, try resolving it as a skill command (/skill-name).
     * If resolved, build the activation message server-side and submit it as
     * a normal user turn so the agent processes the skill content.
     * Returns true if the command was handled (resolved to a skill), false
     * to fall through to "Unknown command".
     */
    private boolean trySkillCommandInvocation(UpdateEvent event, String commandName) {
        if (commandName == null || commandName.isBlank()) return false;
        // Normalize Telegram underscore→hyphen (Hermes resolve_skill_command_key)
        String normalized = commandName.replace('_', '-');
        try {
            BotSessionEntity session = resolveSession(event);
            String sessionId = session != null && session.getBackendSessionId() != null
                ? session.getBackendSessionId().toString() : null;
            String userInstruction = event.commandArgs() == null ? "" : event.commandArgs().strip();
            String activationMessage = backendClient.invokeSkill(normalized, userInstruction, sessionId);
            if (activationMessage == null || activationMessage.isBlank()) {
                return false;
            }
            // Submit the activation message as a normal user turn.
            UpdateEvent skillEvent = new UpdateEvent(
                event.updateId(), UpdateEvent.Type.TEXT, event.chatId(), event.userId(),
                event.username(), event.firstName(), event.languageCode(),
                activationMessage, null, null, null, null, null, null,
                false, null, null, event.messageId(), null, 0, event.forwardedFrom());
            handleTextOrMedia(skillEvent);
            return true;
        } catch (Exception e) {
            log.debug("Skill command invocation for /{} failed: {}", normalized, e.getMessage());
            return false;
        }
    }

    // ─── StreamingOrchestrator.ProcessorHooks ─────────────────────

    @Override
    public String resolveModelUsed(BotSessionEntity session, AgentBackendClient.ChatResult result) {
        if (result != null && result.modelUsed() != null && !result.modelUsed().isBlank()) {
            return result.modelUsed();
        }
        String requested = com.azhukov.agent.bot.config.ModelSelection.forRequest(properties, session.getModelOverride());
        return requested == null || requested.isBlank() ? "backend default" : requested;
    }

    @Override
    public void deliverMedia(long chatId, List<MediaDeliveryService.MediaDescriptor> media, Integer threadId,
                             String owner, java.util.UUID sessionId) {
        if (owner == null || sessionId == null || media.isEmpty()) return;
        var photos = new ArrayList<AttachmentApiClient.Outbound>();
        for (var descriptor : media) {
            attachmentApiClient.loadOutbound(descriptor.artifactId(), owner, sessionId).ifPresent(artifact -> {
                String mime = artifact.mimeType();
                if (mime.startsWith("image/") && !descriptor.asDocument()) {
                    photos.add(artifact);
                    return;
                }
                Optional<Long> sent;
                if (!descriptor.asDocument() && mime.startsWith("video/")) {
                    sent = telegramClient.sendVideo(chatId, artifact.bytes(), artifact.fileName(), null, null);
                } else if (!descriptor.asDocument() && descriptor.asVoice() && mime.startsWith("audio/")) {
                    sent = telegramClient.sendAudioAsVoice(chatId, artifact.bytes(), artifact.fileName(), null);
                } else {
                    sent = telegramClient.sendDocument(chatId, artifact.bytes(), artifact.fileName(), null, null);
                }
                sent.ifPresent(id -> attachmentApiClient.markOutboundDelivered(artifact.id(), owner, sessionId, id));
            });
        }
        int max = MediaDeliveryService.MAX_MEDIA_GROUP_SIZE;
        for (int from = 0; from < photos.size(); from += max) {
            var batch = photos.subList(from, Math.min(from + max, photos.size()));
            if (batch.size() == 1) {
                var photo = batch.getFirst();
                telegramClient.sendPhoto(chatId, photo.bytes(), null, null).ifPresent(
                    id -> attachmentApiClient.markOutboundDelivered(photo.id(), owner, sessionId, id));
            } else {
                var inputs = batch.stream().map(photo -> new TelegramClient.PhotoInput(photo.bytes(), photo.fileName(), null)).toList();
                var receipts = telegramClient.sendMediaGroup(chatId, inputs);
                if (receipts != null && receipts.size() == batch.size()) {
                    for (int i = 0; i < batch.size(); i++) {
                        attachmentApiClient.markOutboundDelivered(batch.get(i).id(), owner, sessionId, receipts.get(i));
                    }
                }
            }
        }
    }

    /**
     * Hermes parity (gateway/run.py _bg_review_send → _deliver_bg_review_message):
     * the self-improvement review summary is delivered to the chat as a regular
     * formatted message AFTER the final answer (pending-release semantics).
     */
    @Override
    public boolean sendReviewMessage(long chatId, String reviewMessage, long userMessageId, long messageThreadId) {
        try {
            return sendFormattedWithReceipt(chatId, reviewMessage, userMessageId, messageThreadId);
        } catch (Exception e) {
            log.warn("Review message delivery failed for chat {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    public String buildMessageWithContext(String messageText, BotSessionEntity session, long chatId) {
        // Hermes parity (gateway/session.py): the platform/user/chat context is
        // injected by the BACKEND into the system prompt volatile tier (backend
        // already receives userId/username/firstName/languageCode/chatType in
        // the chat body and renders "## Current Session Context" there).
        // Prepending it to the user TEXT here duplicated the block into the
        // persisted user message — every history row and session_search hit
        // carried the context header, the model saw it as user input, and
        // mid-turn rotation copied it into the compressed child transcripts.
        return messageText;
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private BotSessionEntity resolveSession(UpdateEvent event) {
        String userId = String.valueOf(event.userId());
        String chatId = String.valueOf(event.chatId());
        String username = event.username();
        // V9: forum topics key their own session (threadId > 0); DMs and
        // non-topic group messages resolve the legacy null-thread lane.
        Long threadId = event.messageThreadId() > 0 ? event.messageThreadId() : null;
        BotSessionEntity session = sessionStore.resolveOrCreate(userId, chatId, username, threadId);
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

    /** WP-11: artifact ids the media handler appended as {@code (artifact=att_...)}. */
    static java.util.List<String> extractArtifactIds(String messageText) {
        if (messageText == null) return java.util.List.of();
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\(artifact=(att_[a-f0-9]+)\\)").matcher(messageText);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** WP-11: remove {@code (artifact=att_...)} markers from user-visible text. */
    static String stripArtifactMarkers(String messageText) {
        if (messageText == null) return null;
        return messageText.replaceAll("\\s*\\(artifact=att_[a-f0-9]+\\)", "");
    }

    private boolean sendFormattedWithReceipt(long chatId, String text, long userMessageId, long messageThreadId) {
        if (responseFilter.shouldFilter(text)) {
            return false;
        }
        Integer threadId = messageThreadId > 0 ? (int) messageThreadId : null;
        String textForDisplay = text;
        if (properties.isMediaDeliveryEnabled()) {
            MediaDeliveryService.ExtractionResult extraction = mediaDeliveryService.extractMediaTags(text);
            textForDisplay = extraction.cleanedText();
        }
        if (textForDisplay == null || textForDisplay.isBlank()) {
            return false;
        }
        String parseMode = properties.getParseMode();
        String formatted = StreamEditor.stripThinkTagsRegex(textForDisplay);
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            formatted = MarkdownConverter.convert(formatted);
        } else if ("HTML".equalsIgnoreCase(parseMode)) {
            formatted = formatted.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        }
        boolean sentAny = false;
        List<String> chunks = MessageSplitter.split(formatted);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (chunk.isBlank()) {
                continue;
            }
            Long replyToMessageId = "all".equalsIgnoreCase(properties.getReplyToMode()) && userMessageId > 0
                || "first".equalsIgnoreCase(properties.getReplyToMode()) && i == 0 && userMessageId > 0
                ? userMessageId : null;
            Optional<Long> sent = threadId == null
                ? telegramClient.sendMessage(chatId, chunk, parseMode, replyToMessageId, null)
                : telegramClient.sendMessage(chatId, chunk, parseMode, replyToMessageId, threadId, false);
            if (sent.isEmpty()) {
                return false;
            }
            sentAny = true;
        }
        return sentAny;
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
