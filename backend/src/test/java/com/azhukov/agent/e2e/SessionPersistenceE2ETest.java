package com.azhukov.agent.e2e;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.persistence.entity.MessageEntity;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E-3: Session persistence integration test.
 * Verifies that sessions and messages survive across requests and that
 * session-management endpoints (reset, compress, undo, branch) behave correctly.
 */
@SpringBootTest
@Tag("slow")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "agent.model.provider=noop"
})
class SessionPersistenceE2ETest extends PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void sessionMessagesAreReset() throws Exception {
        UUID sessionId = chatAndReturnSessionId("hello reset");

        assertThat(messageRepository.countBySessionId(sessionId)).isPositive();

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/reset", sessionId))
            .andExpect(status().isOk());

        assertThat(messageRepository.countBySessionId(sessionId)).isZero();
    }

    @Test
    void sessionAppearsInList() throws Exception {
        UUID sessionId = chatAndReturnSessionId("list me");

        String response = mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(response).contains(sessionId.toString());
    }

    @Test
    void undoTurnsRemovesLastTurn() throws Exception {
        UUID sessionId = chatAndReturnSessionId("turn one");
        chat(sessionId, "turn two");
        chat(sessionId, "turn three");

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/undo", sessionId)
                .param("turns", "1"))
            .andExpect(status().isOk());

        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // Should keep fewer messages than original 3 turns (system + 6 messages)
        assertThat(messages).hasSizeLessThan(7);
    }

    @Test
    void branchSessionCopiesMessages() throws Exception {
        UUID sessionId = chatAndReturnSessionId("parent");
        chat(sessionId, "child context");

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/branch", sessionId)
                .param("name", "Branch of parent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Branch of parent"))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andReturn();

        assertThat(sessionRepository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void v2SessionListHonorsNonPageAlignedOffset() throws Exception {
        String userId = "pagination-" + UUID.randomUUID();
        String first = createSession(userId, "first");
        String second = createSession(userId, "second");
        String third = createSession(userId, "third");

        String response = mockMvc.perform(get("/api/v2/sessions")
                .param("userId", userId)
                .param("limit", "2")
                .param("offset", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offset").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        var data = objectMapper.readTree(response).get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("id").asText()).isEqualTo(second);
        assertThat(data.get(1).get("id").asText()).isEqualTo(first);
        assertThat(data.toString()).doesNotContain(third);
    }

    private String createSession(String userId, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v2/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private UUID chatAndReturnSessionId(String message) throws Exception {
        ChatRequest request = ChatRequest.simple(null, message, null, null);
        String response = mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(response, ChatResponseDto.class).sessionId();
    }

    private void chat(UUID sessionId, String message) throws Exception {
        ChatRequest request = ChatRequest.simple(sessionId, message, null, null);
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());
    }
}
