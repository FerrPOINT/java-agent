package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.typing.TypingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Main message processor — the orchestrator for all incoming {@link UpdateEvent}s.
 *
 * <p>Flow:
 * <ol>
 *   <li>If callback_query → route to {@link CallbackQueryHandler}</li>
 *   <li>If command → route to {@link CommandRegistry}, send response via {@link TelegramClient}</li>
 *   <li>If text/media → check authorization, resolve session, check busy state,
 *       start typing, call {@link AgentBackendClient}, stop typing,
 *       format via {@link MarkdownConverter} + {@link MessageSplitter}, send</li>
 *   <li>On error → send error message to chat</li>
 * </ol>
 */
@Service
public class BotMessageProcessor implements Consumer<UpdateEvent> {

    private static final Logger log = LoggerFactory.getLogger(BotMessageProcessor.class);

    private final TelegramClient telegramClient;
    private final AuthorizationService authorizationService;
    private final BotSessionStore sessionStore;
    private final BusySessionHandler busyHandler;
    private final TypingManager typingManager;
    private final AgentBackendClient backendClient;
    private final CommandRegistry commandRegistry;
    private final CallbackQueryHandler callbackQueryHandler;
    private final BotProperties properties;

    public BotMessageProcessor(TelegramClient telegramClient,
                               AuthorizationService authorizationService,
                               BotSessionStore sessionStore,
                               BusySessionHandler busyHandler,
                               TypingManager typingManager,
                               AgentBackendClient backendClient,
                               CommandRegistry commandRegistry,
                               CallbackQueryHandler callbackQueryHandler,
                               BotProperties properties) {
        this.telegramClient = telegramClient;
        this.authorizationService = authorizationService;
        this.sessionStore = sessionStore;
        this.busyHandler = busyHandler;
        this.typingManager = typingManager;
        this.backendClient = backendClient;
        this.commandRegistry = commandRegistry;
        this.callbackQueryHandler = callbackQueryHandler;
        this.properties = properties;
    }

    @Override
    public void accept(UpdateEvent event) {
        if (event == null) return;

        try {
            switch (event.type()) {
                case CALLBACK_QUERY -> handleCallbackQuery(event);
                case COMMAND -> handleCommand(event);
                case TEXT, PHOTO, DOCUMENT, VOICE, STICKER, ANIMATION -> handleTextOrMedia(event);
                case UNKNOWN -> log.debug("Ignoring UNKNOWN update: {}", event.updateId());
            }
        } catch (Exception e) {
            log.error("Error processing update {}: {}", event.updateId(), e.getMessage(), e);
            sendError(event.chatId(), "An error occurred while processing your message.");
        }
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
        long chatId = event.chatId();

        // Authorization check
        if (!authorizationService.isAuthorized(event)) {
            log.debug("Unauthorized message from userId={} username={} chatId={}",
                event.userId(), event.username(), chatId);
            telegramClient.sendMessage(chatId,
                "⛔ You are not authorized to use this bot.");
            return;
        }

        // Resolve session
        BotSessionEntity session = resolveSession(event);

        // Build message text from the event
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
                log.debug("Interrupting busy chat {}", chatId);
            } else {
                busyHandler.queueMessage(chatId, event);
                log.debug("Queued message for busy chat {}", chatId);
                return;
            }
        }

        // Process the turn
        busyHandler.markBusy(chatId);
        typingManager.startTyping(chatId);

        try {
            String sessionId = session.getId() != null ? session.getId().toString() : null;
            String backendResponse = backendClient.chat(messageText, sessionId);

            sendFormatted(chatId, backendResponse);
        } catch (Exception e) {
            log.error("Backend call failed for chat {}: {}", chatId, e.getMessage(), e);
            sendError(chatId, "Error contacting the agent backend: " + e.getMessage());
        } finally {
            typingManager.stopTyping(chatId);
            busyHandler.markFree(chatId);

            // Drain queued messages (in queue mode)
            if (busyHandler.hasQueued(chatId)) {
                List<UpdateEvent> queued = busyHandler.drainQueue(chatId);
                log.debug("Draining {} queued messages for chat {}", queued.size(), chatId);
                for (UpdateEvent queuedEvent : queued) {
                    accept(queuedEvent);
                }
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private BotSessionEntity resolveSession(UpdateEvent event) {
        String userId = String.valueOf(event.userId());
        String chatId = String.valueOf(event.chatId());
        String username = event.username();
        return sessionStore.resolveOrCreate(userId, chatId, username);
    }

    private String extractMessageText(UpdateEvent event) {
        if (event.text() != null && !event.text().isBlank()) {
            return event.text();
        }
        if (event.caption() != null && !event.caption().isBlank()) {
            return event.caption();
        }
        // For media without caption, send a placeholder
        if (event.fileId() != null) {
            return "[Media attachment: " + (event.fileType() != null ? event.fileType() : "unknown") + "]";
        }
        return null;
    }

    private void sendFormatted(long chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String parseMode = properties.getParseMode();

        // Convert markdown if using MarkdownV2
        String formatted = text;
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            formatted = MarkdownConverter.convert(text);
        }

        // Split and send
        List<String> chunks = MessageSplitter.split(formatted);
        for (String chunk : chunks) {
            if (!chunk.isBlank()) {
                telegramClient.sendMessage(chatId, chunk, parseMode, null, null);
            }
        }
    }

    private void sendError(long chatId, String message) {
        try {
            telegramClient.sendMessage(chatId, message);
        } catch (Exception e) {
            log.error("Failed to send error message to chat {}: {}", chatId, e.getMessage());
        }
    }
}