package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E-1: Bot → Backend → LLM integration test.
 * Runs the full backend with {@code noop} model provider and H2 in-memory DB.
 * Verifies that a chat request creates a session, persists messages, returns a
 * response, and produces usage/context metadata.
 */
@SpringBootTest
@Tag("slow")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=false",
    "agent.model.provider=noop"
})
class AgentControllerBotToLlmIntegrationTest extends PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void syncChatCreatesSessionPersistsMessagesAndReturnsResponse() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "hello", null, null);

        MvcResult result = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("NoOp response: hello"))
            .andExpect(jsonPath("$.sessionId").isNotEmpty())
            .andExpect(jsonPath("$.completed").value(true))
            .andExpect(jsonPath("$.modelUsed").value("unknown"))
            .andReturn();

        ChatResponseDto response = objectMapper.readValue(
            result.getResponse().getContentAsString(), ChatResponseDto.class);
        UUID sessionId = response.sessionId();

        assertThat(sessionRepository.findById(sessionId))
            .isPresent()
            .hasValueSatisfying(session -> {
                assertThat(session.getUserId()).isEqualTo("user-1");
                assertThat(session.getModelProvider()).isEqualTo("openai-compatible");
            });

        // System prompt is rebuilt per turn (Hermes parity), not persisted:
        // persisted history = user + assistant only.
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo("user");
        assertThat(messages.get(0).getContent()).isEqualTo("hello");
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isEqualTo("NoOp response: hello");
    }

    @Test
    void chatWithExistingSessionContinuesConversation() throws Exception {
        ChatRequest first = ChatRequest.simple(null, "first", null, null);

        MvcResult firstResult = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(first)))
            .andExpect(status().isOk())
            .andReturn();

        UUID sessionId = objectMapper.readValue(
            firstResult.getResponse().getContentAsString(), ChatResponseDto.class).sessionId();

        ChatRequest second = ChatRequest.simple(sessionId, "second", null, null);

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(second)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("NoOp response: second"))
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));

        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        assertThat(messages).hasSize(4);
        assertThat(messages).extracting(MessageEntity::getRole)
            .containsExactly("user", "assistant", "user", "assistant");
        assertThat(messages).extracting(MessageEntity::getContent)
            .contains("first", "NoOp response: first", "second", "NoOp response: second");
    }

    @Test
    void streamChatReturnsSseAndPersistsMessages() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "stream me", null, null);

        MvcResult asyncResult = mockMvc.perform(post("/api/v1/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk());

        String content = asyncResult.getResponse().getContentAsString();
        assertThat(content).contains("event:token");
        assertThat(content).contains("event:done");

        assertThat(sessionRepository.count()).isPositive();
        assertThat(messageRepository.count()).isPositive();
    }

    @Test
    void contextEndpointReturnsSessionMetadata() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "meta", null, null);

        MvcResult result = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

        UUID sessionId = objectMapper.readValue(
            result.getResponse().getContentAsString(), ChatResponseDto.class).sessionId();

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/context", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.messageCount").value(2))
            .andExpect(jsonPath("$.toolsUsed").isArray());
    }

    @Test
    void usageEndpointReturnsUsageAfterChat() throws Exception {
        ChatRequest request = ChatRequest.simple(null, "count me", null, null);

        MvcResult result = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

        UUID sessionId = objectMapper.readValue(
            result.getResponse().getContentAsString(), ChatResponseDto.class).sessionId();

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/usage", sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));
    }
}
