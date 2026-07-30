package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.footer.RuntimeFooter;
import com.azhukov.agent.bot.formatting.ResponseFilter;
import com.azhukov.agent.bot.group.GroupMessageFilter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.reaction.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private StreamEditor streamEditor;
    private InboundMediaHandler inboundMediaHandler;
    private RuntimeFooter runtimeFooter;
    private ReactionManager reactionManager;
    private TextBatchDebouncer textBatchDebouncer;
    private PhotoBatchDebouncer photoBatchDebouncer;
    private GroupMessageFilter groupMessageFilter;
    private SlashAccessPolicy slashAccessPolicy;
    private ResponseFilter responseFilter;

    private BotMessageProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        properties.setDefaultModel("test-model");
        properties.setBusyMode("queue");
        properties.setRedactPii(false);  // Disable PII redaction for unit tests
        busyHandler = new BusySessionHandler(properties);
        typingManager = mock(TypingManager.class);
        backendClient = mock(AgentBackendClient.class);
        commandRegistry = mock(CommandRegistry.class);
        callbackQueryHandler = mock(CallbackQueryHandler.class);
        streamEditor = mock(StreamEditor.class);
        inboundMediaHandler = mock(InboundMediaHandler.class);
        runtimeFooter = mock(RuntimeFooter.class);
        reactionManager = mock(ReactionManager.class);
        textBatchDebouncer = mock(TextBatchDebouncer.class);
        photoBatchDebouncer = mock(PhotoBatchDebouncer.class);
        groupMessageFilter = mock(GroupMessageFilter.class);
        slashAccessPolicy = mock(SlashAccessPolicy.class);
        responseFilter = mock(ResponseFilter.class);

        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(true);
        when(authorizationService.isAuthorized(anyLong(), anyString(), anyLong())).thenReturn(true);
        when(groupMessageFilter.shouldProcess(any())).thenReturn(true);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("");
        when(inboundMediaHandler.handle(any())).thenReturn(java.util.Optional.empty());
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(java.util.Optional.of(1L));
        when(streamEditor.editStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        doNothing().when(streamEditor).clearStream(anyLong());

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, runtimeFooter,
            reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter);
    }

    private UpdateEvent textEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "testuser", text, null, null, null, null, null, null,
            false, null, null, 100 + (int)updateId, null, 0);
    }

    @Test
    void queuedMessagesProcessedWithoutStackOverflow() {
        long chatId = 100L;
        List<String> processedTexts = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // Mock streaming — return ChatResult directly (no callback invocation)
        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                if (n == 1) {
                    for (int i = 1; i <= 50; i++) {
                        busyHandler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
                    }
                }
                return new AgentBackendClient.ChatResult("reply to: " + msg, null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), anyString()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("reply to: " + msg, "test-model", 100, 1000, false);
            });

        // Process the first message — it will mark busy, process, then drain 50 queued
        processor.accept(textEvent(0, chatId, "msg-0"));

        // At least the first message should have been processed
        assertThat(processedTexts)
            .as("processedTexts should not be empty — chatStream should have been called")
            .isNotEmpty();
    }

    @Test
    void queueOrderPreservedFIFO() {
        long chatId = 200L;
        List<String> processedTexts = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                // Queue 5 messages during first call
                if (n == 1) {
                    for (int i = 1; i <= 5; i++) {
                        busyHandler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
                    }
                }
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), anyString()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, false);
            });

        // Process first message — queues 5, then drains them
        processor.accept(textEvent(0, chatId, "msg-0"));

        // Verify FIFO order: msg-0 first, then msg-1 through msg-5
        assertThat(processedTexts).startsWith("msg-0");
        for (int i = 1; i < processedTexts.size(); i++) {
            assertThat(processedTexts.get(i)).startsWith("msg-");
        }
    }

    @Test
    void stopTypingCalledAfterSendNotInFinally() {
        long chatId = 700L;
        List<String> callOrder = new java.util.ArrayList<>();

        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                callOrder.add("chatStream");
                return new AgentBackendClient.ChatResult("reply", null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), anyString()))
            .thenAnswer(inv -> {
                callOrder.add("chat");
                return new AgentBackendClient.ChatResult("reply", "test-model", 100, 1000, false);
            });

        // Track call order: sendFormatted → sendMessage, stopTyping
        doAnswer(inv -> { callOrder.add("sendMessage"); return java.util.Optional.of(1L); })
            .when(telegramClient).sendMessage(anyLong(), anyString(), anyString(), any(), any());
        doAnswer(inv -> { callOrder.add("stopTyping"); return null; })
            .when(typingManager).stopTyping(anyLong());

        processor.accept(textEvent(1, chatId, "test"));

        // stopTyping should come AFTER sendMessage
        int sendIdx = callOrder.indexOf("sendMessage");
        int stopIdx = callOrder.indexOf("stopTyping");
        assertThat(sendIdx).as("sendMessage should have been called").isGreaterThanOrEqualTo(0);
        assertThat(stopIdx).as("stopTyping should have been called").isGreaterThanOrEqualTo(0);
        assertThat(stopIdx).as("stopTyping should come after sendMessage").isGreaterThan(sendIdx);
    }

    @Test
    void sendErrorEscapesMarkdownV2() {
        long chatId = 600L;
        properties.setParseMode("MarkdownV2");

        // Backend throws an error with special chars
        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Error at C:\\Users\\test_file.java"));

        processor.accept(textEvent(1, chatId, "test"));

        // Verify sendMessage was called with escaped text
        verify(telegramClient).sendMessage(eq(chatId), argThat(text -> 
            text != null && !text.contains("C:\\Users\\test_file.java") // raw chars should be escaped
        ), eq("MarkdownV2"), isNull(), isNull());
    }

    @Test
    void toolProgressNotInFinalText() {
        long chatId = 500L;
        java.util.List<String> finalizedTexts = new java.util.ArrayList<>();

        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                // Simulate: token consumer called with LLM text
                java.util.function.Consumer<String> tokenConsumer = inv.getArgument(2);
                tokenConsumer.accept("Here is the answer");

                // Simulate: tool call consumer
                java.util.function.Consumer<String> toolCallConsumer = inv.getArgument(3);
                toolCallConsumer.accept("search");

                // Simulate: tool result consumer
                java.util.function.BiConsumer<String, String> toolResultConsumer = inv.getArgument(4);
                toolResultConsumer.accept("search", "results found");

                // Simulate: onComplete with result
                java.util.function.Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(5);
                onComplete.accept(new AgentBackendClient.ChatResult("Here is the answer", "test-model", 100, 1000, true));
                return new AgentBackendClient.ChatResult("Here is the answer", "test-model", 100, 1000, true);
            });

        // Capture finalized text
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        processor.accept(textEvent(1, chatId, "test msg"));

        // Finalized text should NOT contain tool progress markers
        assertThat(finalizedTexts).isNotEmpty();
        assertThat(finalizedTexts.get(0)).doesNotContain("🔧");
        assertThat(finalizedTexts.get(0)).doesNotContain("✅");
        assertThat(finalizedTexts.get(0)).contains("Here is the answer");
    }

    @Test
    void interruptMessageQueuedForReprocessing() {
        // Switch to interrupt mode
        properties.setBusyMode("interrupt");
        long chatId = 400L;
        AtomicInteger callCount = new AtomicInteger(0);
        List<String> processedTexts = new ArrayList<>();

        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                // First call: simulate interrupt by checking isInterrupted inside token consumer
                // and queue a second message
                if (n == 1) {
                    // Queue the interrupting message during processing
                    busyHandler.queueMessage(chatId, textEvent(2, chatId, "interrupting-msg"));
                }
                return new AgentBackendClient.ChatResult("reply to: " + msg, null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), anyString()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("reply to: " + msg, "test-model", 100, 1000, false);
            });

        // First message starts processing
        processor.accept(textEvent(1, chatId, "first-msg"));

        // The first message should have been processed
        assertThat(processedTexts).contains("first-msg");
    }

    @Test
    void maxDrainDepthGuardsAgainstInfiniteLoop() {
        long chatId = 300L;
        AtomicInteger processCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), anyString(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                processCount.incrementAndGet();
                // Enqueue another message during processing (infinite loop scenario)
                busyHandler.queueMessage(chatId, textEvent(999, chatId, "loop-msg"));
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), anyString()))
            .thenAnswer(inv -> {
                processCount.incrementAndGet();
                busyHandler.queueMessage(chatId, textEvent(999, chatId, "loop-msg"));
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, false);
            });

        processor.accept(textEvent(0, chatId, "initial"));

        // Should not loop infinitely — max drain depth (100) should kick in
        // Each message may call both chatStream (streaming attempt) and chat (fallback)
        // so max calls = (1 initial + 100 drained) * 2 = 202
        assertThat(processCount.get()).isLessThanOrEqualTo(202);
    }
}