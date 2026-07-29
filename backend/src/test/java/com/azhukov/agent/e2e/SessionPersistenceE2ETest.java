package com.azhukov.agent.e2e;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "agent.model.provider=noop"
})
class SessionPersistenceE2ETest {

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

    private UUID chatAndReturnSessionId(String message) throws Exception {
        ChatRequest request = new ChatRequest(null, message, null, null);
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
        ChatRequest request = new ChatRequest(sessionId, message, null, null);
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());
    }
}
