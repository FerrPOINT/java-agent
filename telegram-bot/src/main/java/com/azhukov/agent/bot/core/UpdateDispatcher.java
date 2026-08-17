package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.EditCaptureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * c5: Event-type routing for inbound {@link UpdateEvent}s.
 *
 * <p>Extracted from {@link BotMessageProcessor#accept(UpdateEvent)} to keep the
 * processor thin. The dispatcher owns the {@code switch (event.type())} logic
 * and delegates each branch to the {@link Handlers} callback interface, which
 * the processor implements.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Routes by {@link UpdateEvent.Type} to the right handler</li>
 *   <li>Owns the edit-capture short-circuit and the text/photo batch debouncing
 *       decisions (the actual {@link TextBatchDebouncer}/{@link PhotoBatchDebouncer}
 *       instances are injected so the dispatcher can {@code offer()} events)</li>
 *   <li>Catches and logs per-event exceptions, reporting errors to the chat</li>
 * </ul>
 *
 * <p>The dispatchers do <em>not</em> own the per-chat locks or the streaming
 * lifecycle — those stay in {@link BotMessageProcessor} (orchestration) and
 * {@link StreamingOrchestrator} respectively.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UpdateDispatcher {

    private final BotProperties properties;
    private final EditCaptureService editCaptureService;
    private final TextBatchDebouncer textBatchDebouncer;
    private final PhotoBatchDebouncer photoBatchDebouncer;

    /**
     * Callbacks the dispatcher uses to actually handle each event type.
     * Implemented by {@link BotMessageProcessor} so the orchestrator keeps
     * ownership of the per-chat locks and processing logic.
     */
    public interface Handlers {
        /** Handle a callback_query event. */
        void handleCallbackQuery(UpdateEvent event);
        /** Handle a slash-command event. */
        void handleCommand(UpdateEvent event);
        /** Handle a plain text/media event (acquires the per-chat lock). */
        void handleTextOrMedia(UpdateEvent event);
        /** Handle an edited message (acknowledge, do not regenerate). */
        void handleEditedMessage(UpdateEvent event);
        /** Handle a text event routed to edit-capture mode. */
        void handleEditCapture(UpdateEvent event);
        /** Send an error message to the chat (top-level error handler). */
        void sendError(long chatId, String message);
    }

    /**
     * Route an event to the appropriate handler.
     *
     * @param event    the inbound update (must be non-null)
     * @param handlers the handler callbacks (typically the orchestrator itself)
     */
    public void dispatch(UpdateEvent event, Handlers handlers) {
        if (event == null) return;

        try {
            switch (event.type()) {
                case CALLBACK_QUERY -> handlers.handleCallbackQuery(event);
                case COMMAND -> handlers.handleCommand(event);
                case TEXT -> {
                    // P35: Edit-capture mode — if chat has active capture, route to capture handler
                    if (editCaptureService.getCapture(event.chatId()) != null) {
                        handlers.handleEditCapture(event);
                        return;
                    }
                    // B1.3: Text batch/debounce
                    if (properties.getTextBatch() != null && offerTextBatch(event)) {
                        return; // Buffered, will be dispatched later
                    }
                    handlers.handleTextOrMedia(event);
                }
                case PHOTO -> {
                    // B1.4/B1.5: Photo batch/album — if mediaGroupId present, batch
                    if (event.mediaGroupId() != null && !event.mediaGroupId().isBlank()
                        && offerPhotoBatch(event)) {
                        return; // Buffered, will be dispatched later
                    }
                    handlers.handleTextOrMedia(event);
                }
                case DOCUMENT, VOICE, STICKER, ANIMATION, LOCATION -> handlers.handleTextOrMedia(event);
                case EDITED_MESSAGE -> handlers.handleEditedMessage(event);
                case UNKNOWN -> log.debug("Ignoring UNKNOWN update: {}", event.updateId());
                default -> log.debug("Ignoring unhandled update type {} for updateId={}",
                    event.type(), event.updateId());
            }
        } catch (Exception e) {
            log.error("Error processing update {}: {}", event.updateId(), e.getMessage(), e);
            handlers.sendError(event.chatId(), "An error occurred while processing your message.");
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
}