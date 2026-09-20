package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.RuntimeFooter;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.streaming.StreamEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * c5: Unit tests for {@link StreamingOrchestrator} — the streaming chat lifecycle
 * extracted from {@code BotMessageProcessor#streamChat}.
 *
 * <p>Verifies that the orchestrator wires the SSE callbacks (token, tool-call,
 * tool-result, retry, complete, error) to {@link StreamEditor} correctly, appends
 * the footer, extracts MEDIA: tags before finalize, and falls back to sync chat
 * when the stream produced no visible tokens.
 */
class StreamingOrchestratorTest {

    private TelegramClient telegramClient;
    private AgentBackendClient backendClient;
    private StreamEditor streamEditor;
    private BusySessionHandler busyHandler;
    private RuntimeFooter runtimeFooter;
    private BotProperties properties;
    private MediaDeliveryService mediaDeliveryService;
    private StreamingOrchestrator orchestrator;
    private StreamingOrchestrator.ProcessorHooks hooks;
    private final com.azhukov.agent.bot.session.BotSessionStore sessionStoreMock = org.mockito.Mockito.mock(com.azhukov.agent.bot.session.BotSessionStore.class);


    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        backendClient = mock(AgentBackendClient.class);
        streamEditor = mock(StreamEditor.class);
        properties = new BotProperties();
        properties.setStreamEditInterval(Duration.ofMillis(100));
        properties.setParseMode("MarkdownV2");
        properties.setDefaultModel("default-model");
        busyHandler = new BusySessionHandler(properties);
        runtimeFooter = mock(RuntimeFooter.class);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("");
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean())).thenReturn("");
        mediaDeliveryService = new MediaDeliveryService();
        orchestrator = new StreamingOrchestrator(backendClient, streamEditor, busyHandler,
            runtimeFooter, properties, mediaDeliveryService,
            telegramClient, sessionStoreMock,
            mock(com.azhukov.agent.bot.keyboard.ClarifyInteractionRenderer.class));

        // Hooks: the processor's media delivery / model resolution / PII prefix
        hooks = mock(StreamingOrchestrator.ProcessorHooks.class);
        when(hooks.buildMessageWithContext(anyString(), any(), anyLong())).thenAnswer(
            inv -> inv.getArgument(0)); // passthrough when redactPii is false
        when(hooks.resolveModelUsed(any(), any())).thenReturn("resolved-model");

        // StreamEditor defaults
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.of(1L));
        when(streamEditor.editStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString())).thenReturn(true);

        // TelegramClient getMe stub so StreamEditor init works (not strictly needed since editor is mocked)
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of());
        when(telegramClient.callApi(anyString(), any())).thenReturn(Optional.of(meResponse));
        when(telegramClient.getLastApiErrorCode()).thenReturn(0);
    }

    private BotSessionEntity session() {
        BotSessionEntity s = new BotSessionEntity();
        s.setId(UUID.randomUUID());
        return s;
    }

    @SuppressWarnings("unchecked")
    private AgentBackendClient.ChatResult stubChatStream(Consumer<InvocationCtx> setup) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                InvocationCtx ctx = new InvocationCtx(inv);
                setup.accept(ctx);
                return ctx.returnResult;
            });
        return null;
    }

    /** Holds the captured consumers from a chatStream invocation. */
    static class InvocationCtx {
        final String message;
        final String sessionId;
        final Object runtime;
        final Consumer<String> tokenConsumer;
        final Consumer<String> toolCallConsumer;
        final java.util.function.BiConsumer<String, String> toolResultConsumer;
        final Consumer<String> retryConsumer;
        final Consumer<String> clarifyConsumer;
        final Consumer<AgentBackendClient.ChatResult> onComplete;
        final Consumer<Throwable> onError;
        AgentBackendClient.ChatResult returnResult;

        @SuppressWarnings("unchecked")
        InvocationCtx(org.mockito.invocation.InvocationOnMock inv) {
            this.message = inv.getArgument(0);
            this.sessionId = inv.getArgument(1);
            this.runtime = inv.getArgument(2);
            this.tokenConsumer = inv.getArgument(4);
            this.toolCallConsumer = inv.getArgument(5);
            this.toolResultConsumer = inv.getArgument(6);
            this.retryConsumer = inv.getArgument(7);
            this.clarifyConsumer = inv.getArgument(9);
            this.onComplete = inv.getArgument(10);
            this.onError = inv.getArgument(11);
        }
    }

    @Test
    void streamChat_tokens_thenComplete_finalizesStreamAndReturnsContent() {
        stubChatStream(ctx -> {
            ctx.tokenConsumer.accept("Hello ");
            ctx.tokenConsumer.accept("world");
            ctx.onComplete.accept(new AgentBackendClient.ChatResult("Hello world", "model", 10, 100, true));
            ctx.returnResult = new AgentBackendClient.ChatResult("Hello world", "model", 10, 100, true, false, null);
        });

        AgentBackendClient.ChatResult result = orchestrator.streamChat(100L, "hi", null, session(),
            5L, 0L, hooks);

        assertThat(result.content()).isEqualTo("Hello world");
        assertThat(result.streamFinalized()).isTrue();
        // editStream called per token (throttling is in StreamEditor, mocked here)
        verify(streamEditor, atLeast(2)).editStream(eq(100L), eq(1L), anyString());
        // finalizeStream called once on complete
        verify(streamEditor).finalizeStream(eq(100L), eq(1L), anyString());
    }

    @Test
    void streamChat_appendsFooterBeforeFinalize() {
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("\n[footer]");
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean())).thenReturn("\n[footer]");
        stubChatStream(ctx -> {
            ctx.tokenConsumer.accept("answer");
            ctx.onComplete.accept(new AgentBackendClient.ChatResult("answer", "model", 5, 50, true));
            ctx.returnResult = new AgentBackendClient.ChatResult("answer", "model", 5, 50, true, false, null);
        });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        // finalText passed to finalizeStream should include the footer
        verify(streamEditor).finalizeStream(eq(100L), eq(1L), argThat(t -> t.endsWith("[footer]")));
    }

    @Test
    void streamChat_draftCompletionFinalizesTheDraftSession() {
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(Optional.empty()); // Native drafts intentionally have no message id.
        stubChatStream(ctx -> {
            ctx.tokenConsumer.accept("draft answer");
            ctx.onComplete.accept(new AgentBackendClient.ChatResult("draft answer", "model", 1, 10, true));
            ctx.returnResult = new AgentBackendClient.ChatResult("draft answer", "model", 1, 10, true, false, null);
        });

        AgentBackendClient.ChatResult result = orchestrator.streamChat(100L, "hi", null, session(),
            5L, 0L, hooks);

        assertThat(result.streamFinalized()).isTrue();
        verify(streamEditor).finalizeStream(100L, -1L, "draft answer");
        verify(streamEditor, never()).sendFormattedFinalMessage(anyLong(), anyString());
    }

    @Test
    void streamChat_draftFinalDeliveryFailureDoesNotClaimFinalization() {
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(Optional.empty());
        when(streamEditor.finalizeStream(100L, -1L, "draft answer")).thenReturn(false);
        stubChatStream(ctx -> {
            ctx.tokenConsumer.accept("draft answer");
            ctx.onComplete.accept(new AgentBackendClient.ChatResult("draft answer", "model", 1, 10, true));
            ctx.returnResult = new AgentBackendClient.ChatResult("draft answer", "model", 1, 10, true, false, null);
        });

        AgentBackendClient.ChatResult result = orchestrator.streamChat(100L, "hi", null, session(),
            5L, 0L, hooks);

        assertThat(result.streamFinalized()).isFalse();
        verify(streamEditor).recordFinalDeliveryFailure(100L, "draft answer");
    }

    @Test
    void streamChat_interrupted_finalizesWithAccumulatedContent() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(4);
                Consumer<Throwable> onError = inv.getArgument(11);
                // First token streams normally (editStream is called)
                tokenConsumer.accept("partial");
                // Now mark the chat interrupted mid-stream and emit another token,
                // which makes the orchestrator's token consumer throw StreamInterruptedException.
                busyHandler.markBusy(100L);
                busyHandler.interrupt(100L);
                try {
                    tokenConsumer.accept(" more");
                } catch (StreamingOrchestrator.StreamInterruptedException e) {
                    // Mirror real backend: catch the interrupt and signal via onError
                    onError.accept(e);
                }
                return new AgentBackendClient.ChatResult("partial more", "model", 1, 10, true, false, null);
            });

        AgentBackendClient.ChatResult result = orchestrator.streamChat(100L, "hi", null, session(),
            5L, 0L, hooks);

        // Interrupted → onError finalizes with the accumulated content (partial + " more")
        verify(streamEditor).finalizeStream(eq(100L), eq(1L), eq("partial more"));
        assertThat(result.content()).isEqualTo("partial more");
    }

    @Test
    void streamChat_errorFinalizesWithUserFriendlyMessage() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<Throwable> onError = inv.getArgument(11);
                onError.accept(new RuntimeException("HTTP 429"));
                return new AgentBackendClient.ChatResult("", "model", 1, 10, false, false, null);
            });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        // On error, finalizeStream called with the user-friendly error message
        verify(streamEditor).finalizeStream(eq(100L), eq(1L), argThat(t -> t.contains("Rate limited by Telegram")));
    }

    @Test
    void streamChat_noContentWithMetadata_fallsBackToSync() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                // No tokens, no complete — just metadata
                return new AgentBackendClient.ChatResult("", "model", 1, 10, false, false, null);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("sync answer", "model", 1, 10, false, false, null));

        AgentBackendClient.ChatResult result = orchestrator.streamChat(100L, "hi", null, session(),
            5L, 0L, hooks);

        // Should fall back to sync chat
        verify(backendClient).chat(anyString(), nullable(String.class), any());
        assertThat(result.content()).isEqualTo("sync answer");
        // finalizeStream NOT called (no streaming message)
        verify(streamEditor, never()).finalizeStream(anyLong(), anyLong(), anyString());
    }

    @Test
    void streamChat_chatStreamThrows_clearsStreamWhenMessageExistsAndRethrows() {
        // startStream succeeds → messageId >= 0 → clearStream is invoked on failure
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.of(1L));
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("connection refused"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Streaming failed");

        // On exception with a live streaming message, clearStream cleans up
        verify(streamEditor).clearStream(eq(100L));
    }

    @Test
    void streamChat_chatStreamThrows_whenNoMessage_clearsDraftSessionAndRethrows() {
        // Native drafts have no message id but still own a heartbeat/session that must be cleared.
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.empty());
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong())).thenReturn(Optional.empty());
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("connection refused"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks))
            .isInstanceOf(RuntimeException.class);

        verify(streamEditor).clearStream(100L);
    }

    @Test
    void streamChat_toolProgressVisibleByDefaultRendersExecutedTool() {
        stubChatStream(ctx -> {
            ctx.toolCallConsumer.accept("web_search\u0001{\"query\":\"latest news\"}");
            ctx.returnResult = new AgentBackendClient.ChatResult("", null, null, null, true, false, null);
        });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        verify(telegramClient).sendMessage(eq(100L), contains("web_search"), isNull(), isNull(), isNull(), eq(true));
    }

    @Test
    void streamChat_clarifyToolStartRendersOnlyBoundKeyboard() {
        var boundRenderer = mock(com.azhukov.agent.bot.keyboard.ClarifyInteractionRenderer.class);
        orchestrator = new StreamingOrchestrator(backendClient, streamEditor, busyHandler,
            runtimeFooter, properties, mediaDeliveryService, telegramClient, sessionStoreMock, boundRenderer);

        stubChatStream(ctx -> {
            ctx.toolCallConsumer.accept("clarify\u0001{\"question\":\"Target?\",\"choices\":[\"dev\",\"prod\"]}");
            ctx.clarifyConsumer.accept("{\"clarifyId\":\"prompt-1\",\"question\":\"Target?\",\"choices\":[\"dev\",\"prod\"]}");
            ctx.returnResult = new AgentBackendClient.ChatResult("", null, null, null, true, false, null);
        });

        orchestrator.streamChat(100L, "hi", "session-1", session(), 5L, 0L, hooks);

        verify(boundRenderer).present(eq(100L), eq(0L), eq("session-1"), contains("prompt-1"));
    }

    @Test
    void streamChat_hiddenToolProgressDoesNotRenderExecutedTool() {
        properties.getDisplay().setToolProgress("hidden");
        stubChatStream(ctx -> {
            ctx.toolCallConsumer.accept("web_search\u0001{\"query\":\"latest news\"}");
            ctx.returnResult = new AgentBackendClient.ChatResult("", null, null, null, true, false, null);
        });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        verify(telegramClient, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    void streamChat_delayedStartCommitsTextBeforeToolProgress() {
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong()))
            .thenReturn(Optional.empty());
        stubChatStream(ctx -> {
            ctx.tokenConsumer.accept("preface");
            ctx.toolCallConsumer.accept("session_search\u0001{\"query\":\"recent\"}");
            ctx.returnResult = new AgentBackendClient.ChatResult("", null, null, null, true, false, null);
        });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        InOrder order = inOrder(streamEditor);
        order.verify(streamEditor).onSegmentBreak(100L, -1L, "preface");
        order.verify(streamEditor).setCurrentToolName(100L, "session_search");
    }

    @Test
    void streamChat_toolCall_setsCurrentToolName() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(4);
                Consumer<String> toolCallConsumer = inv.getArgument(5);
                tokenConsumer.accept("thinking");
                toolCallConsumer.accept("WebSearch\u0001{\"q\":\"x\"}");
                // Post-tool tokens stream into a NEW segment (the tool-call consumer
                // committed the previous segment and reset the accumulator).
                tokenConsumer.accept("after tool");
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult("after tool", "model", 1, 10, true));
                return new AgentBackendClient.ChatResult("after tool", "model", 1, 10, true, false, null);
            });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        verify(streamEditor).setCurrentToolName(eq(100L), eq("WebSearch"));
    }

    @Test
    void streamChat_toolResult_triggersSegmentBreak() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(4);
                java.util.function.BiConsumer<String, String> toolResultConsumer = inv.getArgument(6);
                tokenConsumer.accept("before tool");
                toolResultConsumer.accept("WebSearch", "result-preview");
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult("before tool", "model", 1, 10, true));
                return new AgentBackendClient.ChatResult("before tool", "model", 1, 10, true, false, null);
            });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        // Segment break no longer fires on tool_result — the live currentMessageId
        // is read on the next token batch instead (ordering fix). Tool result only
        // advances the accumulated text here.
        verify(streamEditor, never()).onSegmentBreak(anyLong(), anyLong(), anyString());
    }

    @Test
    void streamChat_retryConsumer_showsRetryStatus() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(4);
                Consumer<String> retryConsumer = inv.getArgument(7);
                tokenConsumer.accept("partial");
                retryConsumer.accept("Retrying due to rate limit...");
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult("partial", "model", 1, 10, true));
                return new AgentBackendClient.ChatResult("partial", "model", 1, 10, true, false, null);
            });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        // editStream called with accumulated + retry message
        verify(streamEditor).editStream(eq(100L), eq(1L), argThat(t -> t.contains("Retrying due to rate limit")));
    }

    @Test
    void streamChat_usesHooksForModelResolution() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(4);
                tokenConsumer.accept("answer");
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult("answer", "model", 1, 10, true));
                return new AgentBackendClient.ChatResult("answer", "model", 1, 10, true, false, null);
            });

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        // The footer's first arg (model) should come from hooks.resolveModelUsed
        verify(runtimeFooter).format(eq("resolved-model"), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean());
    }

    @Test
    void streamChat_buildsMessageWithContextViaHooks() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                Consumer<String> tokenConsumer = inv.getArgument(4);
                tokenConsumer.accept(msg); // emit the (context-prefixed) message as a token
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult(msg, "model", 1, 10, true));
                return new AgentBackendClient.ChatResult(msg, "model", 1, 10, true, false, null);
            });

        when(hooks.buildMessageWithContext(anyString(), any(), anyLong())).thenReturn("CTX:hi");

        orchestrator.streamChat(100L, "hi", null, session(), 5L, 0L, hooks);

        verify(backendClient).chatStream(eq("CTX:hi"), nullable(String.class), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
    }

}