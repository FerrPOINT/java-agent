package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final StreamEditor streamEditor;
    private final InboundMediaHandler inboundMediaHandler;

    public BotMessageProcessor(TelegramClient telegramClient,
                               AuthorizationService authorizationService,
                               BotSessionStore sessionStore,
                               BusySessionHandler busyHandler,
                               TypingManager typingManager,
                               AgentBackendClient backendClient,
                               CommandRegistry commandRegistry,
                               CallbackQueryHandler callbackQueryHandler,
                               BotProperties properties,
                               StreamEditor streamEditor,
                               InboundMediaHandler inboundMediaHandler) {
        this.telegramClient = telegramClient;
        this.authorizationService = authorizationService;
        this.sessionStore = sessionStore;
        this.busyHandler = busyHandler;
        this.typingManager = typingManager;
        this.backendClient = backendClient;
        this.commandRegistry = commandRegistry;
        this.callbackQueryHandler = callbackQueryHandler;
        this.properties = properties;
        this.streamEditor = streamEditor;
        this.inboundMediaHandler = inboundMediaHandler;
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

            // Use streaming: accumulate tokens via StreamEditor, check for interrupts
            String backendResponse = streamChat(chatId, messageText, sessionId);

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

    // ─── Streaming ────────────────────────────────────────────────

    /**
     * Stream chat from the backend, editing the message in-place via StreamEditor.
     * Falls back to synchronous chat() if streaming fails to start.
     * Checks for interrupts during streaming.
     *
     * @param chatId      the Telegram chat ID
     * @param messageText the user's message
     * @param sessionId   the session UUID string (may be null)
     * @return the full accumulated response text
     */
    private String streamChat(long chatId, String messageText, String sessionId) {
        StringBuilder accumulated = new StringBuilder();
        final long[] messageId = {-1};

        // Try streaming first
        try {
            // Start with an initial message
            Optional<Long> initialMsgId = streamEditor.startStream(chatId, "…");
            if (initialMsgId.isPresent()) {
                messageId[0] = initialMsgId.get();
            }

            backendClient.chatStream(messageText, sessionId,
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
                // onComplete
                () -> {
                    if (messageId[0] >= 0 && accumulated.length() > 0) {
                        streamEditor.finalizeStream(chatId, messageId[0], accumulated.toString());
                    }
                },
                // onError
                error -> {
                    if (error instanceof StreamInterruptedException) {
                        // Interrupted — finalize with what we have
                        if (messageId[0] >= 0 && accumulated.length() > 0) {
                            streamEditor.finalizeStream(chatId, messageId[0],
                                accumulated + "\n\n[Interrupted by user]");
                        }
                    } else {
                        log.error("Stream error for chat {}: {}", chatId, error.getMessage());
                        // Finalize with partial content + error
                        if (messageId[0] >= 0) {
                            String errorText = accumulated.length() > 0
                                ? accumulated + "\n\n[Error: " + error.getMessage() + "]"
                                : "[Error: " + error.getMessage() + "]";
                            streamEditor.finalizeStream(chatId, messageId[0], errorText);
                        }
                    }
                }
            );

            // If streaming produced content, return it
            if (accumulated.length() > 0) {
                return accumulated.toString();
            }
        } catch (StreamInterruptedException e) {
            // Already handled in onError callback
            return accumulated.toString();
        } catch (Exception e) {
            log.warn("Streaming failed for chat {}, falling back to sync: {}", chatId, e.getMessage());
            // Clean up the initial message if streaming failed
            if (messageId[0] >= 0) {
                streamEditor.clearStream(chatId);
            }
        }

        // Fallback to synchronous chat
        return backendClient.chat(messageText, sessionId);
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
        if (text == null || text.isBlank()) {
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

        List<String> chunks = MessageSplitter.split(formatted);
        for (String chunk : chunks) {
            if (!chunk.isBlank()) {
                telegramClient.sendMessage(chatId, chunk, parseMode, null, null);
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
            telegramClient.sendMessage(chatId, message);
        } catch (Exception e) {
            log.error("Failed to send error message to chat {}: {}", chatId, e.getMessage());
        }
    }

    /** Pattern to match MEDIA:/path in response text. */
    private static final java.util.regex.Pattern MEDIA_PATTERN =
        java.util.regex.Pattern.compile("MEDIA:(\\S+)");
}