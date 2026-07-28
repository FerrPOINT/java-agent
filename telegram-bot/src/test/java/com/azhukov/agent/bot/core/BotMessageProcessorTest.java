package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.typing.TypingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotMessageProcessorTest {

    private TelegramClient telegramClient;
    private AuthorizationService authorizationService;
    private BotSessionStore sessionStore;
    private BusySessionHandler busyHandler;
    private TypingManager typingManager;
    private AgentBackendClient backendClient;
    private CommandRegistry commandRegistry;
    private CallbackQueryHandler callbackQueryHandler;
    private BotProperties properties;
    private BotMessageProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        busyHandler = mock(BusySessionHandler.class);
        typingManager = mock(TypingManager.class);
        backendClient = mock(AgentBackendClient.class);
        commandRegistry = mock(CommandRegistry.class);
        callbackQueryHandler = mock(CallbackQueryHandler.class);
        properties = new BotProperties();
        properties.setParseMode("MarkdownV2");
        properties.setBusyMode("queue");

        processor = new BotMessageProcessor(telegramClient, authorizationService, sessionStore,
            busyHandler, typingManager, backendClient, commandRegistry,
            callbackQueryHandler, properties);

        // Default: authorized
        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(true);
        // Default: not busy
        when(busyHandler.isBusy(anyLong())).thenReturn(false);
        when(busyHandler.getBusyMode()).thenReturn("queue");
        // Default: session
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString()))
            .thenReturn(makeSession());
        // Default: sendMessage returns a message ID
        when(telegramClient.sendMessage(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(telegramClient.sendMessage(anyLong(), anyString(), anyString(), any(), any())).thenReturn(Optional.of(1L));
    }

    // ─── Text message flow ─────────────────────────────────────────

    @Test
    void accept_textMessage_callsBackendAndSendsResponse() {
        UpdateEvent event = textEvent(1L, "Hello agent");
        when(backendClient.chat(eq("Hello agent"), anyString())).thenReturn("Hello human!");

        processor.accept(event);

        verify(typingManager).startTyping(event.chatId());
        verify(backendClient).chat(eq("Hello agent"), anyString());
        verify(typingManager).stopTyping(event.chatId());
        verify(busyHandler).markBusy(event.chatId());
        verify(busyHandler).markFree(event.chatId());
        verify(telegramClient).sendMessage(eq(event.chatId()), anyString(), eq("MarkdownV2"), isNull(), isNull());
    }

    @Test
    void accept_textMessage_resolvesSession() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(backendClient.chat(anyString(), anyString())).thenReturn("OK");

        processor.accept(event);

        verify(sessionStore).resolveOrCreate(
            String.valueOf(event.userId()),
            String.valueOf(event.chatId()),
            event.username()
        );
    }

    @Test
    void accept_captionText_isSentToBackend() {
        UpdateEvent event = new UpdateEvent(1L, UpdateEvent.Type.PHOTO, 100L, 200L,
            "jdoe", null, "Photo caption text", "file123", "photo",
            null, null, null, false, null, null);
        when(backendClient.chat(eq("Photo caption text"), anyString())).thenReturn("Nice photo!");

        processor.accept(event);

        verify(backendClient).chat(eq("Photo caption text"), anyString());
        verify(telegramClient).sendMessage(eq(100L), anyString(), eq("MarkdownV2"), isNull(), isNull());
    }

    @Test
    void accept_mediaWithoutCaption_sendsPlaceholderToBackend() {
        UpdateEvent event = new UpdateEvent(1L, UpdateEvent.Type.PHOTO, 100L, 200L,
            "jdoe", null, null, "file123", "photo",
            null, null, null, false, null, null);
        when(backendClient.chat(anyString(), anyString())).thenReturn("Got it");

        processor.accept(event);

        verify(backendClient).chat(eq("[Media attachment: photo]"), anyString());
    }

    // ─── Command flow ──────────────────────────────────────────────

    @Test
    void accept_command_routesToCommandRegistry() {
        UpdateEvent event = commandEvent(1L, "help", "");
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.name()).thenReturn("help");
        when(handler.description()).thenReturn("Show help");
        when(handler.handle(eq(event), any())).thenReturn("Available commands: ...");
        when(commandRegistry.get("help")).thenReturn(handler);

        processor.accept(event);

        verify(commandRegistry).get("help");
        verify(handler).handle(eq(event), any(BotSessionEntity.class));
        verify(telegramClient).sendMessage(eq(event.chatId()), anyString(), eq("MarkdownV2"), isNull(), isNull());
    }

    @Test
    void accept_unknownCommand_sendsUnknownMessage() {
        UpdateEvent event = commandEvent(1L, "nonexistent", "");
        when(commandRegistry.get("nonexistent")).thenReturn(null);

        processor.accept(event);

        verify(telegramClient).sendMessage(eq(event.chatId()), contains("Unknown command"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void accept_command_doesNotCallBackend() {
        UpdateEvent event = commandEvent(1L, "help", "");
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(eq(event), any())).thenReturn("Help text");
        when(commandRegistry.get("help")).thenReturn(handler);

        processor.accept(event);

        verifyNoInteractions(backendClient);
    }

    @Test
    void accept_command_doesNotCheckBusyOrTyping() {
        UpdateEvent event = commandEvent(1L, "help", "");
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(eq(event), any())).thenReturn("Help text");
        when(commandRegistry.get("help")).thenReturn(handler);

        processor.accept(event);

        verifyNoInteractions(typingManager);
    }

    // ─── Unauthorized message ──────────────────────────────────────

    @Test
    void accept_unauthorizedMessage_sendsDeniedMessage() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(authorizationService.isAuthorized(event)).thenReturn(false);

        processor.accept(event);

        verify(telegramClient).sendMessage(eq(event.chatId()), contains("not authorized"));
        verifyNoInteractions(backendClient);
        verifyNoInteractions(typingManager);
    }

    @Test
    void accept_unauthorizedMessage_doesNotResolveSession() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(authorizationService.isAuthorized(event)).thenReturn(false);

        processor.accept(event);

        verifyNoInteractions(sessionStore);
    }

    // ─── Busy session queues ───────────────────────────────────────

    @Test
    void accept_busySessionInQueueMode_queuesMessage() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(busyHandler.isBusy(event.chatId())).thenReturn(true);
        when(busyHandler.getBusyMode()).thenReturn("queue");

        processor.accept(event);

        verify(busyHandler).queueMessage(event.chatId(), event);
        verifyNoInteractions(backendClient);
        verifyNoInteractions(typingManager);
    }

    @Test
    void accept_busySessionInInterruptMode_doesNotQueueButInterrupts() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(busyHandler.isBusy(event.chatId())).thenReturn(true);
        when(busyHandler.getBusyMode()).thenReturn("interrupt");
        when(backendClient.chat(anyString(), anyString())).thenReturn("Response");

        // In interrupt mode, the processor still marks busy and processes
        // (the interrupt signals to the in-flight turn, but we let the new
        // message through after calling interrupt)
        processor.accept(event);

        verify(busyHandler).interrupt(event.chatId());
        // The message still goes through the backend call
        verify(backendClient).chat(eq("Hello"), anyString());
    }

    @Test
    void accept_afterProcessing_drainsQueuedMessages() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(busyHandler.isBusy(event.chatId())).thenReturn(false);
        when(backendClient.chat(anyString(), anyString())).thenReturn("Response");
        when(busyHandler.hasQueued(event.chatId())).thenReturn(true);
        when(busyHandler.drainQueue(event.chatId())).thenReturn(List.of());

        processor.accept(event);

        verify(busyHandler).hasQueued(event.chatId());
        verify(busyHandler).drainQueue(event.chatId());
    }

    @Test
    void accept_noQueuedMessages_doesNotDrain() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(busyHandler.isBusy(event.chatId())).thenReturn(false);
        when(backendClient.chat(anyString(), anyString())).thenReturn("Response");
        when(busyHandler.hasQueued(event.chatId())).thenReturn(false);

        processor.accept(event);

        verify(busyHandler, never()).drainQueue(anyLong());
    }

    // ─── Error handling ────────────────────────────────────────────

    @Test
    void accept_backendException_sendsErrorMessage() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(backendClient.chat(anyString(), anyString()))
            .thenThrow(new RuntimeException("Backend down"));

        processor.accept(event);

        verify(telegramClient).sendMessage(eq(event.chatId()), contains("Error"));
        verify(typingManager).stopTyping(event.chatId());
        verify(busyHandler).markFree(event.chatId());
    }

    @Test
    void accept_commandException_sendsErrorMessage() {
        UpdateEvent event = commandEvent(1L, "help", "");
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(eq(event), any()))
            .thenThrow(new RuntimeException("Command crashed"));
        when(commandRegistry.get("help")).thenReturn(handler);

        processor.accept(event);

        verify(telegramClient).sendMessage(eq(event.chatId()), contains("Error"));
    }

    @Test
    void accept_backendReturnsErrorString_stillSendsFormatted() {
        UpdateEvent event = textEvent(1L, "Hello");
        when(backendClient.chat(anyString(), anyString()))
            .thenReturn("Error: something went wrong");

        processor.accept(event);

        verify(telegramClient).sendMessage(eq(event.chatId()), contains("Error: something went wrong"),
            eq("MarkdownV2"), isNull(), isNull());
    }

    // ─── Callback query ────────────────────────────────────────────

    @Test
    void accept_callbackQuery_routesToCallbackHandler() {
        UpdateEvent event = callbackEvent("cq-1", "model:gpt-4");
        when(callbackQueryHandler.handle(event)).thenReturn("Model switched to gpt-4");

        processor.accept(event);

        verify(callbackQueryHandler).handle(event);
        verify(telegramClient).sendMessage(eq(event.chatId()), eq("Model switched to gpt-4"));
    }

    @Test
    void accept_callbackQuery_nullResponse_doesNotSend() {
        UpdateEvent event = callbackEvent("cq-1", "unknown");
        when(callbackQueryHandler.handle(event)).thenReturn(null);

        processor.accept(event);

        verify(callbackQueryHandler).handle(event);
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    // ─── Null/Unknown ──────────────────────────────────────────────

    @Test
    void accept_nullEvent_doesNothing() {
        processor.accept(null);
        verifyNoInteractions(telegramClient);
    }

    @Test
    void accept_unknownType_doesNothing() {
        UpdateEvent event = new UpdateEvent(1L, UpdateEvent.Type.UNKNOWN, 0, 0,
            "", null, null, null, null, null, null, null, false, null, null);

        processor.accept(event);

        verifyNoInteractions(backendClient);
        verifyNoInteractions(typingManager);
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private UpdateEvent textEvent(long updateId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, 100L, 200L,
            "jdoe", text, null, null, null,
            null, null, null, false, null, null);
    }

    private UpdateEvent commandEvent(long updateId, String command, String args) {
        return new UpdateEvent(updateId, UpdateEvent.Type.COMMAND, 100L, 200L,
            "jdoe", "/" + command + " " + args, null, null, null,
            null, null, null, true, command, args);
    }

    private UpdateEvent callbackEvent(String cqId, String data) {
        return new UpdateEvent(1L, UpdateEvent.Type.CALLBACK_QUERY, 100L, 200L,
            "jdoe", null, null, null, null,
            cqId, data, null, false, null, null);
    }

    private BotSessionEntity makeSession() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId("200");
        session.setChatId("100");
        session.setUsername("jdoe");
        session.setActive(true);
        return session;
    }
}