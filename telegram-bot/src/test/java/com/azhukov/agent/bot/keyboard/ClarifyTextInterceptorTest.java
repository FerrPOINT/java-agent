package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ClarifyTextInterceptorTest {

    private MockRestServiceServer server;
    private ClarifyTextInterceptor interceptor;
    private ClarifyInteractionRenderer renderer;
    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://backend.test");
        server = MockRestServiceServer.bindTo(builder).build();
        renderer = mock(ClarifyInteractionRenderer.class);
        interceptor = new ClarifyTextInterceptor(builder.build(), new ObjectMapper(), renderer);
        session = new BotSessionEntity();
        session.setId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
        session.setChatId("12345");
    }

    @Test
    void customTextResolvedByBackendUnblocksTheExistingClarifyTurn() {
        String backendSessionId = "550e8400-e29b-41d4-a716-446655440001";
        when(renderer.awaitingResponseSession(12345L)).thenReturn(backendSessionId);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/"
                + backendSessionId + "/clarify/text"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"text\":\"a bespoke environment\"}"))
            .andRespond(withSuccess("{\"outcome\":\"resolved\"}", MediaType.APPLICATION_JSON));

        assertThat(interceptor.tryResolve(session, "a bespoke environment")).isEqualTo("resolved");
        verify(renderer).completeTextResponse(12345L);
        server.verify();
    }

    @Test
    void typedChoiceWithoutOtherUsesTheLivePromptSessionAndUnblocksTheTurn() {
        String backendSessionId = "550e8400-e29b-41d4-a716-446655440002";
        when(renderer.awaitingResponseSession(12345L)).thenReturn(backendSessionId);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/"
                + backendSessionId + "/clarify/text"))
            .andExpect(content().json("{\"text\":\"2\"}"))
            .andRespond(withSuccess("{\"outcome\":\"resolved\"}", MediaType.APPLICATION_JSON));

        assertThat(interceptor.tryResolve(session, "2")).isEqualTo("resolved");
        verify(renderer).completeTextResponse(12345L);
        server.verify();
    }

    @Test
    void rejectedChoiceRemainsWithThePromptAndNeverFallsIntoBusyInterrupt() {
        String backendSessionId = "550e8400-e29b-41d4-a716-446655440003";
        when(renderer.awaitingResponseSession(12345L)).thenReturn(backendSessionId);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/"
                + backendSessionId + "/clarify/text"))
            .andRespond(withSuccess("{\"outcome\":\"rejected_selection\"}", MediaType.APPLICATION_JSON));

        assertThat(interceptor.tryResolve(session, "9")).isEqualTo("invalid_selection");
        server.verify();
    }

    @Test
    void unresolvedProseDuringVisibleChoicePromptIsConsumedInsteadOfInterrupting() {
        String backendSessionId = "550e8400-e29b-41d4-a716-446655440004";
        when(renderer.awaitingResponseSession(12345L)).thenReturn(backendSessionId);
        when(renderer.isAwaitingResponse(12345L)).thenReturn(true);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/"
                + backendSessionId + "/clarify/text"))
            .andRespond(withSuccess("{\"outcome\":\"rejected_prose\"}", MediaType.APPLICATION_JSON));

        assertThat(interceptor.tryResolve(session, "not a listed option")).isEqualTo("awaiting_response");
        server.verify();
    }
    @Test
    void backendErrorDuringAVisiblePromptIsConsumedInsteadOfInterruptingTheTurn() {
        String backendSessionId = "550e8400-e29b-41d4-a716-446655440005";
        when(renderer.awaitingResponseSession(12345L)).thenReturn(backendSessionId);
        when(renderer.isAwaitingResponse(12345L)).thenReturn(true);
        server.expect(once(), requestTo("http://backend.test/api/v1/agent/session/"
                + backendSessionId + "/clarify/text"))
            .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));

        assertThat(interceptor.tryResolve(session, "delayed answer")).isEqualTo("awaiting_response");
        server.verify();
    }

    @Test
    void absentPendingClarifyLeavesAnOrdinaryMessageForNormalRouting() {
        assertThat(interceptor.tryResolve(session, "ordinary message")).isNull();
        server.verify();
    }
}
