package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionCrudControllerTest {

    private MockMvc mockMvc;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private AgentRuntimeService agentRuntimeService;
    private AgentStreamingService streamingService;
    private AgentSessionResolver sessionResolver;
    private SessionEntityMapper sessionMapper;
    private DomainDtoMapper domainDtoMapper;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        agentRuntimeService = mock(AgentRuntimeService.class);
        streamingService = mock(AgentStreamingService.class);
        sessionResolver = mock(AgentSessionResolver.class);
        sessionMapper = mock(SessionEntityMapper.class);
        domainDtoMapper = mock(DomainDtoMapper.class);
        properties = mock(AgentProperties.class);

        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("test-model");
        when(properties.getModel()).thenReturn(modelProps);

        mockMvc = MockMvcBuilders.standaloneSetup(new SessionCrudController(
            sessionRepository, messageRepository, agentRuntimeService, streamingService,
            sessionResolver, sessionMapper, domainDtoMapper, properties)).build();
    }

    @Test
    void listSessionsReturnsPaginatedList() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setTitle("Test session");
        entity.setModelName("test-model");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        when(sessionRepository.findAllByUserId(eq("user-1"), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));
        // M15: Mock the count query used for has_more
        when(sessionRepository.countByUserId("user-1")).thenReturn(1L);

        Session domain = new Session(sessionId, "user-1", "Test session",
            "openai-compatible", "test-model", null, java.util.Map.of(), null);
        when(sessionMapper.toDomain(entity)).thenReturn(domain);
        when(domainDtoMapper.toSessionSummaryDto(domain))
            .thenReturn(new SessionSummaryDto(sessionId, "user-1", "Test session",
                "openai-compatible", "test-model", Instant.now(), Instant.now()));

        mockMvc.perform(get("/api/v2/sessions?limit=10&offset=0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].id").value(sessionId.toString()))
            .andExpect(jsonPath("$.data[0].title").value("Test session"))
            .andExpect(jsonPath("$.limit").value(10))
            .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void createSessionReturns201() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user-1", "New chat",
            "openai-compatible", "test-model", null, java.util.Map.of(), null);
        when(sessionResolver.createSession("user-1", "openai-compatible", "test-model"))
            .thenReturn(session);

        mockMvc.perform(post("/api/v2/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My Session\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.object").value("session"))
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.title").value("My Session"));
    }

    @Test
    void getSessionReturns404ForMissing() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(sessionRepository.findById(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v2/sessions/" + missingId))
            .andExpect(status().isNotFound());
    }

    @Test
    void getSessionReturnsSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setTitle("Test session");
        entity.setModelName("test-model");

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/v2/sessions/" + sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("session"))
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.title").value("Test session"));
    }

    @Test
    void patchSessionUpdatesTitle() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setTitle("Old title");
        entity.setModelName("test-model");

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(entity);

        mockMvc.perform(patch("/api/v2/sessions/" + sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"New title\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("New title"));

        verify(sessionRepository).save(any(SessionEntity.class));
    }

    @Test
    void deleteSessionRemovesSessionAndMessages() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        mockMvc.perform(delete("/api/v2/sessions/" + sessionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("session.deleted"))
            .andExpect(jsonPath("$.id").value(sessionId.toString()))
            .andExpect(jsonPath("$.deleted").value(true));

        verify(sessionRepository).delete(entity);
        verify(messageRepository).deleteAll(List.of());
    }

    @Test
    void deleteSessionReturns404ForMissing() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(sessionRepository.findById(missingId)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v2/sessions/" + missingId))
            .andExpect(status().isNotFound());
    }

    @Test
    void getSessionMessagesReturnsMessages() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);

        MessageEntity msg = new MessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setSessionId(sessionId);
        msg.setRole("user");
        msg.setContent("Hello");
        msg.setCreatedAt(Instant.now());

        // M16: Mock the Pageable version of findBySessionIdOrderByCreatedAtAsc
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(eq(sessionId), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(msg)));

        mockMvc.perform(get("/api/v2/sessions/" + sessionId + "/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].role").value("user"))
            .andExpect(jsonPath("$.data[0].content").value("Hello"));
    }

    @Test
    void getSessionMessagesReturns404ForMissingSession() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(sessionRepository.existsById(missingId)).thenReturn(false);

        mockMvc.perform(get("/api/v2/sessions/" + missingId + "/messages"))
            .andExpect(status().isNotFound());
    }

    @Test
    void sessionChatRunsTurn() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);

        ChatResponseDto response = new ChatResponseDto(sessionId, "Hello back", List.of(), true);
        when(agentRuntimeService.runTurn(any())).thenReturn(response);

        mockMvc.perform(post("/api/v2/sessions/" + sessionId + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
            .andExpect(jsonPath("$.content").value("Hello back"))
            .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void sessionChatReturns404ForMissingSession() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(sessionRepository.existsById(missingId)).thenReturn(false);

        mockMvc.perform(post("/api/v2/sessions/" + missingId + "/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hello\"}"))
            .andExpect(status().isNotFound());
    }
}