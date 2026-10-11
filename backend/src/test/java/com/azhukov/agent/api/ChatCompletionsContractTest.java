package com.azhukov.agent.api;

import com.azhukov.agent.support.NoopSpringIntegrationTest;
import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for OpenAI chat-completions parity behaviors:
 * 1. omitted `model` must NOT 400 — the configured advertised model is used;
 * 2. X-Hermes-Session-Id continuity header is honored and echoed back.
 */
class ChatCompletionsContractTest extends NoopSpringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String SESSION_HEADER = "X-Hermes-Session-Id";

    @Test
    void omittedModelIsAccepted() throws Exception {
        String body = """
            {"messages":[{"role":"user","content":"hi"}]}
            """;
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-key-123")
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    void sessionContinuityHeaderEchoedBack() throws Exception {
        // With a configured API key, continuation is authorized and the
        // response echoes the session header (Hermes parity).
        // External (non-UUID) session ids map deterministically to a backend
        // session and are echoed verbatim (Hermes external-session semantics).
        String sessionId = "chat-continuity-" + System.nanoTime();
        String body = """
            {"model":"noop","messages":[{"role":"user","content":"remember TOKEN_XYZ"}]}
            """;
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-key-123")
                .header(SESSION_HEADER, sessionId)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        String echoed = result.getResponse().getHeader(SESSION_HEADER);
        assertThat(echoed).isEqualTo(sessionId);
    }

    @Test
    void blankModelIsAccepted() throws Exception {
        String body = """
            {"model":"","messages":[{"role":"user","content":"hi"}]}
            """;
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer test-key-123")
                .content(body))
            .andExpect(status().isOk());
    }
}
