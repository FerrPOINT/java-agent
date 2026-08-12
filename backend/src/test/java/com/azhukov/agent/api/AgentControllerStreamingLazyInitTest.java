package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the SSE streaming endpoint POST /api/v1/agent/chat/stream
 * does not throw LazyInitializationException when a session has a
 * lazily-loaded cliState collection.
 * <p>
 * SessionEntity.cliState is an @ElementCollection with FetchType.LAZY.
 * The streaming path runs in an async thread outside any transaction;
 * without the fix, accessing cliState during streaming throws
 * LazyInitializationException.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb-lazyinit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "agent.model.provider=noop"
})
class AgentControllerStreamingLazyInitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void streamChatWithCliState_doesNotThrowLazyInitializationException() throws Exception {
        // Create a session with cliState values in a transaction
        UUID sessionId = transactionTemplate.execute(status -> {
            SessionEntity entity = new SessionEntity();
            entity.setUserId("user-1");
            entity.setModelProvider("openai-compatible");
            entity.setModelName("noop-model");
            entity.setTitle("Lazy init test");
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            // Set several cliState values to ensure the lazy collection is populated
            entity.setCliStateValue("goal", "fix the streaming bug");
            entity.setCliStateValue("subgoals", "step1\nstep2\nstep3");
            entity.setCliStateValue("reasoningEffort", "high");
            entity.setCliStateValue("personality", "concise");
            entity.setCliStateValue("goalPaused", "false");
            entity.setCliStateValue("queuedPrompt", "Remember to test the fix");
            SessionEntity saved = sessionRepository.save(entity);
            return saved.getId();
        });

        // Stream a chat message to the session — this exercises the code path
        // where SessionEntity is loaded and cliState is accessed during streaming
        ChatRequest request = new ChatRequest(sessionId, "hello", null, 30_000L);

        MvcResult result = mockMvc.perform(post("/api/v1/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        String content = result.getResponse().getContentAsString();

        // The stream should complete with token and done events, not an error
        assertThat(content).contains("event:done");
        assertThat(content).doesNotContain("LazyInitializationException");
        assertThat(content).doesNotContain("could not initialize proxy");
        assertThat(content).doesNotContain("no Session");
    }
}