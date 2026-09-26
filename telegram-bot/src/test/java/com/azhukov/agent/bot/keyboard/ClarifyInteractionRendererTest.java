package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.typing.TypingManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClarifyInteractionRendererTest {

    private TelegramClient telegramClient;
    private TypingManager typingManager;
    private MockRestServiceServer server;
    private ClarifyInteractionRenderer renderer;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        typingManager = mock(TypingManager.class);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend.test");
        server = MockRestServiceServer.bindTo(builder).build();
        renderer = new ClarifyInteractionRenderer(
            new ObjectMapper(), new InlineKeyboardBuilder(new ObjectMapper()), builder.build(),
            telegramClient, typingManager);
    }

    @Test
    void deliveredClarifyPromptPausesTypingUntilTheUserAnswers() {
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(Optional.of(42L));

        renderer.present(100L, 0L, "session-1", """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);

        verify(typingManager).pauseTypingForClarify(100L);
    }

    @Test
    void failedClarifyPromptDoesNotPauseTyping() {
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(Optional.empty());

        renderer.present(100L, 0L, "session-1", """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);

        verifyNoInteractions(typingManager);
    }

    @Test
    void missingBackendSessionDoesNotRenderAnUnresolvablePrompt() {
        renderer.present(100L, 0L, null, """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);

        verifyNoInteractions(telegramClient, typingManager);
    }

    @Test
    void otherButtonArmsCustomTextOnBackendBeforePromptingForIt() {
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(Optional.of(42L));
        renderer.present(100L, 0L, "session-1", """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/session-1/clarify/prompt-1/custom"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andRespond(withSuccess("{\"armed\":true}", MediaType.APPLICATION_JSON));

        ClarifyInteractionRenderer.CallbackResult result = renderer.handleCallback(100L, 42L, "prompt-1:other");

        assertThat(result).isEqualTo(new ClarifyInteractionRenderer.CallbackResult("Type your answer", false));
        assertThat(renderer.awaitingTextSession(100L)).isEqualTo("session-1");
        server.verify();
    }

    @Test
    void callbackFromAnotherChatCannotArmOrResolveThePrompt() {
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(Optional.of(42L));
        renderer.present(100L, 0L, "session-1", """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);

        ClarifyInteractionRenderer.CallbackResult result = renderer.handleCallback(200L, 42L, "prompt-1:other");

        assertThat(result).isEqualTo(new ClarifyInteractionRenderer.CallbackResult("This question has expired.", false));
        assertThat(renderer.awaitingTextSession(200L)).isNull();
        server.verify();
    }

    @Test
    void resolvedChoiceResumesTypingAfterBackendAcceptsIt() {
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(Optional.of(42L));
        renderer.present(100L, 0L, "session-1", """
            {"clarifyId":"prompt-1","question":"Deploy where?","choices":["dev","prod"]}
            """);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/session-1/clarify/resolve"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"clarifyId\":\"prompt-1\",\"response\":\"1\"}"))
            .andRespond(withSuccess("{\"resolved\":true}", MediaType.APPLICATION_JSON));

        ClarifyInteractionRenderer.CallbackResult result = renderer.handleCallback(100L, 42L, "prompt-1:0");

        verify(typingManager).resumeTyping(100L);
        server.verify();
        org.assertj.core.api.Assertions.assertThat(result.complete()).isTrue();
    }
}
