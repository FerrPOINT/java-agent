package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.streaming.StreamEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0: a model error arriving BEFORE any token (draft streaming keeps messageId at -1)
 * must still notify the user — a standalone plain message with a user-friendly error,
 * plus StreamSession cleanup. Regression test for the "silent bot under billing quota"
 * bug: the user saw nothing at all (no error text, no footer) because onError only
 * handled the messageId >= 0 case.
 */
class StreamingOrchestratorErrorBeforeFirstTokenTest {

    private AgentBackendClient backendClient;
    private StreamEditor streamEditor;
    private BusySessionHandler busyHandler;
    private RuntimeFooter runtimeFooter;
    private BotProperties properties;

    private StreamingOrchestrator orchestrator;
    private StreamingOrchestrator.ProcessorHooks hooks;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        streamEditor = mock(StreamEditor.class);
        properties = new BotProperties();
        properties.setStreamEditInterval(Duration.ofMillis(100));
        properties.setParseMode("MarkdownV2");
        properties.setDefaultModel("default-model");
        properties.setRedactPii(false);
        busyHandler = new BusySessionHandler(properties);
        runtimeFooter = mock(RuntimeFooter.class);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("");
        orchestrator = new StreamingOrchestrator(backendClient, streamEditor, busyHandler,
            runtimeFooter, properties, new MediaDeliveryService());

        hooks = mock(StreamingOrchestrator.ProcessorHooks.class);
        when(hooks.buildMessageWithContext(anyString(), any(), anyLong()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(hooks.resolveModelUsed(any(), any())).thenReturn("resolved-model");

        // Draft streaming: startStream returns empty — no message id exists yet
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong()))
            .thenReturn(Optional.empty());
        when(streamEditor.sendFormattedFinalMessage(anyLong(), anyString())).thenReturn(Optional.of(5L));
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString())).thenReturn(true);
    }

    private BotSessionEntity session() {
        BotSessionEntity s = new BotSessionEntity();
        s.setId(UUID.randomUUID());
        return s;
    }

    @SuppressWarnings("unchecked")
    private void stubErrorBeforeTokens(String errorMessage) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(),
            any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<Throwable> onError = inv.getArgument(8);
                onError.accept(new RuntimeException(errorMessage));
                return new AgentBackendClient.ChatResult("");
            });
    }

    @Test
    void streamError_beforeAnyToken_sendsStandaloneError() {
        stubErrorBeforeTokens("you (user) have reached your weekly usage limit, "
            + "add extra usage: https://ollama.com/settings");

        AgentBackendClient.ChatResult result = orchestrator.streamChat(
            100L, "hi", null, session(), 1L, 0L, hooks);

        // The user MUST receive a standalone message with the friendly error
        verify(streamEditor).sendFormattedFinalMessage(eq(100L), contains("usage limit reached"));
        // The draft session and its heartbeat must be cleaned up
        verify(streamEditor).clearStream(100L);
        // The turn is finalized so the processor does NOT send a footer-only garbage message
        assertThat(result.streamFinalized()).isTrue();
        verify(streamEditor, never()).finalizeStream(anyLong(), anyLong(), anyString());
    }

    @Test
    void streamError_billingError_userFriendlyTextMentionsBilling() {
        stubErrorBeforeTokens("you (user) have reached your weekly usage limit, "
            + "add extra usage: https://ollama.com/settings");

        orchestrator.streamChat(100L, "hi", null, session(), 1L, 0L, hooks);

        verify(streamEditor).sendFormattedFinalMessage(eq(100L), argThat(text ->
            text != null && text.toLowerCase().contains("billing")));
    }

    @Test
    void streamError_transientUsageLimit_showsRateLimitedNotBilling() {
        // "usage limit" + "try again later" = transient rate limit, not billing
        stubErrorBeforeTokens("usage limit exceeded, please try again later");

        orchestrator.streamChat(100L, "hi", null, session(), 1L, 0L, hooks);

        verify(streamEditor).sendFormattedFinalMessage(eq(100L), argThat(text ->
            text != null && text.toLowerCase().contains("rate limited")
                && !text.toLowerCase().contains("billing")));
    }

    @Test
    void streamInterrupted_beforeAnyToken_cleansUpDraftSession() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(),
            any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept("he"); // first token arrives…
                Consumer<Throwable> onError = inv.getArgument(8);
                onError.accept(new StreamingOrchestrator.StreamInterruptedException());
                return new AgentBackendClient.ChatResult("");
            });

        orchestrator.streamChat(100L, "hi", null, session(), 1L, 0L, hooks);

        verify(streamEditor).clearStream(100L);
    }
}
