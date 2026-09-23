package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regression coverage for backend-session and chat binding of blocking clarify prompts. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class ClarifyInteractionRendererTest {

    private static final long OWNER_CHAT = 100L;
    private static final long OTHER_CHAT = 200L;
    private static final String BACKEND_SESSION = "550e8400-e29b-41d4-a716-446655440000";

    @Mock private TelegramClient telegramClient;
    @Mock private RestClient backendRestClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private ClarifyInteractionRenderer renderer;

    @BeforeEach
    void setUp() {
        when(telegramClient.sendMessage(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(9L));
        renderer = new ClarifyInteractionRenderer(new ObjectMapper(), new InlineKeyboardBuilder(new ObjectMapper()),
            backendRestClient, telegramClient);
    }

    @Test
    void crossChatCallbackDoesNotResolveThePrompt() {
        renderer.present(OWNER_CHAT, 0, BACKEND_SESSION,
            "{\"clarifyId\":\"clarify-1\",\"question\":\"Deploy?\",\"choices\":[\"dev\"]}");

        ClarifyInteractionRenderer.CallbackResult result = renderer.handleCallback(OTHER_CHAT, 9L, "clarify-1:0");

        assertThat(result.complete()).isFalse();
        assertThat(result.acknowledgement()).contains("another chat");
        verify(backendRestClient, never()).post();
    }

    @Test
    void typedReplyUsesBackendSessionRatherThanBotRowId() {
        renderer.present(OWNER_CHAT, 0, BACKEND_SESSION,
            "{\"clarifyId\":\"clarify-1\",\"question\":\"Deploy?\",\"choices\":[\"dev\"]}");
        BotSessionEntity botSession = new BotSessionEntity();
        botSession.setId(UUID.fromString("660e8400-e29b-41d4-a716-446655440000"));
        ClarifyTextInterceptor interceptor = new ClarifyTextInterceptor(backendRestClient, new ObjectMapper(), renderer);
        when(backendRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(eq("/api/v1/agent/session/{sessionId}/clarify/text"), eq(BACKEND_SESSION))).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("outcome", "resolved"));

        assertThat(interceptor.tryResolve(botSession, OWNER_CHAT, "1")).isEqualTo("resolved");
        verify(postSpec).uri(eq("/api/v1/agent/session/{sessionId}/clarify/text"), eq(BACKEND_SESSION));
        assertThat(renderer.backendSessionIdForChat(OWNER_CHAT)).isNull();
    }

    @Test
    void rejectedProseStaysInTheClarifyRoute() {
        renderer.present(OWNER_CHAT, 0, BACKEND_SESSION,
            "{\"clarifyId\":\"clarify-1\",\"question\":\"Deploy?\",\"choices\":[\"dev\"]}");
        BotSessionEntity botSession = new BotSessionEntity();
        botSession.setId(UUID.randomUUID());
        ClarifyTextInterceptor interceptor = new ClarifyTextInterceptor(backendRestClient, new ObjectMapper(), renderer);
        when(backendRestClient.post()).thenReturn(postSpec);
        when(postSpec.uri(eq("/api/v1/agent/session/{sessionId}/clarify/text"), eq(BACKEND_SESSION))).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(Map.class)).thenReturn(Map.of("outcome", "rejected_prose"));

        assertThat(interceptor.tryResolve(botSession, OWNER_CHAT, "please decide for me")).isEqualTo("awaiting_clarify");
        assertThat(renderer.backendSessionIdForChat(OWNER_CHAT)).isEqualTo(BACKEND_SESSION);
    }
}
