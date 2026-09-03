package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.HermesSessionStreamingService;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.ProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionCrudControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID FORK_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440000");
    private static final UUID LEAF_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440000");
    private static final Instant BASE_TIME = Instant.parse("2026-08-28T10:00:00Z");

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private AgentRuntimeService agentRuntimeService;
    private HermesSessionStreamingService streamingService;
    private AgentSessionResolver sessionResolver;
    private AgentProperties properties;
    private AgentProperties.ApiProperties apiProperties;
    private ApiRunAdmissionService runAdmissionService;
    private MockMvc mockMvc;
    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        agentRuntimeService = mock(AgentRuntimeService.class);
        streamingService = mock(HermesSessionStreamingService.class);
        sessionResolver = mock(AgentSessionResolver.class);
        SessionEntityMapper sessionMapper = mock(SessionEntityMapper.class);
        DomainDtoMapper domainDtoMapper = mock(DomainDtoMapper.class);
        properties = mock(AgentProperties.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        AgentProperties.ModelProperties modelProperties = new AgentProperties.ModelProperties();
        modelProperties.setModelName("gpt-test");
        apiProperties = new AgentProperties.ApiProperties();
        AgentProperties.SecurityProperties securityProperties = new AgentProperties.SecurityProperties();
        securityProperties.setApiKey("secret");
        AgentProperties.ProfileProperties profileProperties = new AgentProperties.ProfileProperties();
        profileProperties.setBaseDir(tempDir.resolve("profiles").toString());
        Files.createDirectories(tempDir.resolve("profiles").resolve("work"));
        when(properties.getModel()).thenReturn(modelProperties);
        when(properties.getApi()).thenReturn(apiProperties);
        when(properties.getSecurity()).thenReturn(securityProperties);
        when(properties.getProfile()).thenReturn(profileProperties);
        runAdmissionService = new ApiRunAdmissionService(properties);
        ProfileService profileService = new ProfileService(properties, new com.azhukov.agent.service.RuntimeConfigService());
        lenient().when(sessionResolver.resolveResumeSessionId(any(UUID.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc = MockMvcBuilders.standaloneSetup(new SessionCrudController(
                sessionRepository,
                messageRepository,
                agentRuntimeService,
                streamingService,
                sessionResolver,
                sessionMapper,
                domainDtoMapper,
                new ObjectMapper(),
                properties,
                eventPublisher,
                runAdmissionService,
                profileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listSessionsAcceptsHermesCanonicalAndLegacyV2Routes() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), anyInt(), anyInt(), eq(false), eq(false), eq(false),
                eq(null), eq(null), eq(false), eq(true)))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.sessions").isArray())
            .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v2/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.sessions").isArray());
    }

    @Test
    void profilePrefixedListSessionsRouteMirrorsHermesMultiplexAlias() throws Exception {
        when(sessionRepository.findPageByUserIdAndProfileOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), eq("work"), anyInt(), anyInt(), eq(false), eq(false), eq(false),
                eq(null), eq(null), eq(false), eq(true)))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserIdAndProfile(
                AgentProperties.DEFAULT_USER_ID, "work", false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/p/work/api/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.sessions").isArray())
            .andExpect(jsonPath("$.total").value(0));

        verify(sessionRepository).findPageByUserIdAndProfileOrderByRecent(
            AgentProperties.DEFAULT_USER_ID, "work", 50, 0, false, false, false, null, null, false, true);
        verify(sessionRepository).countVisibleByUserIdAndProfile(
            AgentProperties.DEFAULT_USER_ID, "work", false, false, false, null, null, false, true);
    }

    @Test
    void listSessionsHonorsOffsetThatIsNotPageAligned() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                AgentProperties.DEFAULT_USER_ID, 2, 1, false, false, false, null, null, false, true))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(3L);

        mockMvc.perform(get("/api/sessions")
                .param("limit", "2")
                .param("offset", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(2))
            .andExpect(jsonPath("$.offset").value(1))
            .andExpect(jsonPath("$.has_more").value(true));

        verify(sessionRepository).findPageByUserIdOrderByRecent(
            AgentProperties.DEFAULT_USER_ID, 2, 1, false, false, false, null, null, false, true);
    }

    @Test
    void listSessionsCoercesInvalidPaginationLikeHermes() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), anyInt(), anyInt(), eq(false), eq(false), eq(false),
                eq(null), eq(null), eq(false), eq(true)))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions")
                .param("limit", "nope")
                .param("offset", "-10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void listSessionsCapsOutOfRangePaginationLikeHermes() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), anyInt(), anyInt(), eq(false), eq(false), eq(false),
                eq(null), eq(null), eq(false), eq(true)))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions")
                .param("limit", "500")
                .param("offset", "1000001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(200))
            .andExpect(jsonPath("$.offset").value(1000000));

        mockMvc.perform(get("/api/sessions")
                .param("limit", "-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.offset").value(0));

        mockMvc.perform(get("/api/sessions")
                .param("offset", "-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(50))
            .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void listSessionsSupportsHermesRecentOrder() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                AgentProperties.DEFAULT_USER_ID, 50, 0, false, false, false, null, null, false, true))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions")
                .param("order", "recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order").value("recent"));

        verify(sessionRepository).findPageByUserIdOrderByRecent(
            AgentProperties.DEFAULT_USER_ID, 50, 0, false, false, false, null, null, false, true);
    }

    @Test
    void listSessionsCoercesUnknownOrderLikeHermes() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                eq(AgentProperties.DEFAULT_USER_ID), anyInt(), anyInt(), eq(false), eq(false), eq(false),
                eq(null), eq(null), eq(false), eq(true)))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions")
                .param("order", "updated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order").value("recent"));
    }

    @Test
    void listSessionsReturnsHermesPayloadAndCanIncludeHiddenArchivedSessions() throws Exception {
        SessionEntity entity = sessionEntity("Pinned archive");
        entity.setPinned(true);
        entity.setArchived(true);
        entity.setHidden(true);
        entity.setEndReason("idle_timeout");
        when(sessionRepository.findPageByUserIdOrderByRecent(
                "user-1", 10, 0, true, false, true, null, "Pinned archive", false, true))
            .thenReturn(List.of(entity));
        when(sessionRepository.countVisibleByUserId(
                "user-1", true, false, true, null, "Pinned archive", false, true))
            .thenReturn(1L);

        mockMvc.perform(get("/api/sessions")
                .param("userId", "user-1")
                .param("limit", "10")
                .param("includeArchived", "true")
                .param("title", "Pinned archive")
                .param("includeHidden", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.data[0].user_id").value("user-1"))
            .andExpect(jsonPath("$.data[0].started_at").value(Math.toIntExact(BASE_TIME.minusSeconds(60).getEpochSecond())))
            .andExpect(jsonPath("$.data[0].ended_at").value(Math.toIntExact(BASE_TIME.minusSeconds(30).getEpochSecond())))
            .andExpect(jsonPath("$.data[0].last_active").value(Math.toIntExact(BASE_TIME.getEpochSecond())))
            .andExpect(jsonPath("$.data[0].is_active").value(false))
            .andExpect(jsonPath("$.data[0].message_count").value(2))
            .andExpect(jsonPath("$.data[0].tool_call_count").value(0))
            .andExpect(jsonPath("$.data[0].input_tokens").value(0))
            .andExpect(jsonPath("$.data[0].output_tokens").value(0))
            .andExpect(jsonPath("$.data[0].preview").value("preview"))
            .andExpect(jsonPath("$.data[0].pinned").value(true))
            .andExpect(jsonPath("$.data[0].archived").value(true))
            .andExpect(jsonPath("$.data[0].hidden").value(true))
            .andExpect(jsonPath("$.data[0].profile").value("default"))
            .andExpect(jsonPath("$.data[0].is_default_profile").value(true));
    }

    @Test
    void listSessionsSupportsHermesArchivedOnlyFilter() throws Exception {
        SessionEntity archived = sessionEntity("Archived");
        archived.setArchived(true);
        when(sessionRepository.findPageByUserIdOrderByRecent(
                AgentProperties.DEFAULT_USER_ID, 50, 0, true, true, false, null, null, false, false))
            .thenReturn(List.of(archived));
        when(sessionRepository.countVisibleByUserId(
                AgentProperties.DEFAULT_USER_ID, true, true, false, null, null, false, false))
            .thenReturn(1L);

        mockMvc.perform(get("/api/sessions")
                .param("archived", "only")
                .param("include_pinned", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessions[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.sessions[0].archived").value(true))
            .andExpect(jsonPath("$.total").value(1));

        verify(sessionRepository).findPageByUserIdOrderByRecent(
            AgentProperties.DEFAULT_USER_ID, 50, 0, true, true, false, null, null, false, false);
    }

    @Test
    void listSessionsRejectsInvalidHermesArchivedFilter() throws Exception {
        mockMvc.perform(get("/api/sessions")
                .param("archived", "all"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("archived must be one of: exclude, only, include"))
            .andExpect(jsonPath("$.error.code").value("invalid_archived_filter"));
    }

    @Test
    void listSessionsIgnoresIncludeHiddenWithoutTitleLikeHermes() throws Exception {
        when(sessionRepository.findPageByUserIdOrderByRecent(
                "user-1", 10, 0, false, false, false, null, null, false, true))
            .thenReturn(List.of());
        when(sessionRepository.countVisibleByUserId(
                "user-1", false, false, false, null, null, false, true))
            .thenReturn(0L);

        mockMvc.perform(get("/api/sessions")
                .param("userId", "user-1")
                .param("limit", "10")
                .param("includeHidden", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));

        verify(sessionRepository).findPageByUserIdOrderByRecent(
            "user-1", 10, 0, false, false, false, null, null, false, true);
    }

    @Test
    void listSessionsSupportsHermesFiltersAndPinnedBackfill() throws Exception {
        SessionEntity pinned = sessionEntity("Bot Chat");
        pinned.setPinned(true);
        SessionEntity recent = sessionEntity("Bot Chat");
        recent.setId(UUID.fromString("770e8400-e29b-41d4-a716-446655440000"));
        recent.setLastActive(BASE_TIME.minusSeconds(10));
        when(sessionRepository.findPinnedByUserIdOrderByRecent(
                "user-1", false, false, true, "telegram", "Bot Chat", true))
            .thenReturn(List.of(pinned));
        when(sessionRepository.findPageByUserIdOrderByRecent(
                "user-1", 1, 0, false, false, true, "telegram", "Bot Chat", true, true))
            .thenReturn(List.of(recent));
        when(sessionRepository.countVisibleByUserId(
                "user-1", false, false, true, "telegram", "Bot Chat", true, true))
            .thenReturn(1L);

        mockMvc.perform(get("/api/sessions")
                .param("user_id", "user-1")
                .param("limit", "1")
                .param("source", "telegram")
                .param("title", " Bot Chat ")
                .param("include_children", "on")
                .param("include_hidden", "yes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].pinned").value(true))
            .andExpect(jsonPath("$.data[1].id").value(recent.getId().toString()))
            .andExpect(jsonPath("$.has_more").value(false));
    }

    @Test
    void bulkDeleteSessionsSkipsUnknownAndInvalidIdsLikeHermes() throws Exception {
        when(sessionRepository.findExistingIds(List.of(SESSION_ID, FORK_ID)))
            .thenReturn(List.of(SESSION_ID));

        mockMvc.perform(post("/api/sessions/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of(
                    "ids", List.of(SESSION_ID.toString(), "not-a-uuid", FORK_ID.toString())
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.deleted").value(1));

        verify(sessionRepository).orphanChildrenOf(List.of(SESSION_ID));
        verify(messageRepository).deleteBySessionIdIn(List.of(SESSION_ID));
        verify(sessionRepository).deleteAllByIdInBatch(List.of(SESSION_ID));
    }

    @Test
    void bulkDeleteSessionsCapsIdsLikeHermes() throws Exception {
        List<String> ids = IntStream.range(0, 501)
            .mapToObj(i -> UUID.nameUUIDFromBytes(("session-" + i).getBytes(StandardCharsets.UTF_8)).toString())
            .toList();

        mockMvc.perform(post("/api/sessions/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(Map.of("ids", ids))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("ids must contain at most 500 entries"));
    }

    @Test
    void emptySessionEndpointsUseHermesSafeSelector() throws Exception {
        when(sessionRepository.countEmptyEndedUnarchived()).thenReturn(2L);
        when(sessionRepository.findEmptyEndedUnarchivedIds()).thenReturn(List.of(SESSION_ID));
        when(sessionRepository.findExistingIds(List.of(SESSION_ID))).thenReturn(List.of(SESSION_ID));

        mockMvc.perform(get("/api/sessions/empty/count"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(delete("/api/sessions/empty"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.deleted").value(1));

        verify(sessionRepository).orphanChildrenOf(List.of(SESSION_ID));
        verify(messageRepository).deleteBySessionIdIn(List.of(SESSION_ID));
        verify(sessionRepository).deleteAllByIdInBatch(List.of(SESSION_ID));
    }

    @Test
    void sessionStatsReturnsHermesSummaryShape() throws Exception {
        when(sessionRepository.count()).thenReturn(5L);
        when(sessionRepository.countUnarchivedSessions()).thenReturn(3L);
        when(sessionRepository.countArchivedSessions()).thenReturn(2L);
        when(messageRepository.count()).thenReturn(9L);
        when(sessionRepository.countTopLevelSessionsBySource())
            .thenReturn(List.of(new Object[] {"cli", 4L}, new Object[] {"", 1L}));

        mockMvc.perform(get("/api/sessions/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.active_store").value(3))
            .andExpect(jsonPath("$.archived").value(2))
            .andExpect(jsonPath("$.messages").value(9))
            .andExpect(jsonPath("$.by_source.cli").value(4))
            .andExpect(jsonPath("$.by_source.unknown").value(1));
    }

    @Test
    void sessionImportImportsExportedRowsAndSkipsDuplicatesLikeHermes() throws Exception {
        when(sessionRepository.existsById(any(UUID.class))).thenReturn(false, true);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String payload = """
            {
              "sessions": [
                {
                  "id": "imported-web-session",
                  "source": "cli",
                  "title": "Imported from dashboard",
                  "started_at": 100.0,
                  "ended_at": 110.0,
                  "end_reason": "complete",
                  "messages": [
                    {"role": "user", "content": "hello", "timestamp": 101.0},
                    {"role": "assistant", "content": "hi", "timestamp": 102.0}
                  ]
                }
              ]
            }
            """;

        mockMvc.perform(post("/api/sessions/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.skipped").value(0))
            .andExpect(jsonPath("$.imported_ids[0]").value("imported-web-session"));

        ArgumentCaptor<SessionEntity> sessionCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getId()).isNotNull();
        assertThat(sessionCaptor.getValue().getCliStateValue("imported_external_id"))
            .isEqualTo("imported-web-session");
        assertThat(sessionCaptor.getValue().getTitle()).isEqualTo("Imported from dashboard");
        assertThat(sessionCaptor.getValue().getMessageCount()).isEqualTo(2);
        assertThat(sessionCaptor.getValue().getProfile()).isEqualTo("default");
        assertThat(sessionCaptor.getValue().getPreview()).isEqualTo("hello");
        assertThat(sessionCaptor.getValue().getCreatedAt().getEpochSecond()).isEqualTo(100);
        assertThat(sessionCaptor.getValue().getUpdatedAt().getEpochSecond()).isEqualTo(110);

        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, times(2)).save(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues()).extracting(MessageEntity::getContent)
            .containsExactly("hello", "hi");
        assertThat(messageCaptor.getAllValues()).extracting(MessageEntity::getRole)
            .containsExactly("user", "assistant");

        mockMvc.perform(post("/api/sessions/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(0))
            .andExpect(jsonPath("$.skipped").value(1))
            .andExpect(jsonPath("$.skipped_ids[0]").value("imported-web-session"));
    }

    @Test
    void sessionImportReportsPerRowErrorsLikeHermes() throws Exception {
        mockMvc.perform(post("/api/sessions/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Invalid session import payload"));

        mockMvc.perform(post("/api/sessions/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessions\":[{\"source\":\"cli\",\"messages\":[]}]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail.ok").value(false))
            .andExpect(jsonPath("$.detail.errors[0].index").value(0))
            .andExpect(jsonPath("$.detail.errors[0].error").value("session id is required"));

        mockMvc.perform(post("/api/sessions/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessions\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(0))
            .andExpect(jsonPath("$.skipped").value(0))
            .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void sessionPruneDryRunUsesHermesFilterPrecedenceAndSkipsOpenSessions() throws Exception {
        SessionEntity candidate = pruneCandidate(SESSION_ID, "Old cron", "cron", "complete", 2);
        when(sessionRepository.findPruneCandidates(
                eq("work"), isNull(), isNull(), isNull(), eq("cron"), eq("old"), eq("complete"),
                eq("user-1"), eq(1), eq(3), eq("gpt"), eq(false)))
            .thenReturn(List.of(candidate));
        when(sessionRepository.countOpenPruneMatches(
                eq("work"), isNull(), isNull(), isNull(), eq("cron"), eq("old"), eq("complete"),
                eq("user-1"), eq(1), eq(3), eq("gpt"), eq(false)))
            .thenReturn(1L);

        mockMvc.perform(post("/p/work/api/sessions/prune")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "source": "cron",
                      "title_like": "old",
                      "end_reason": "complete",
                      "user_id": "user-1",
                      "min_messages": 1,
                      "max_messages": 3,
                      "model_like": "gpt",
                      "dry_run": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.removed").value(0))
            .andExpect(jsonPath("$.matched").value(1))
            .andExpect(jsonPath("$.skipped_open").value(1))
            .andExpect(jsonPath("$.oldest_last_active").value(BASE_TIME.minusSeconds(120).getEpochSecond()))
            .andExpect(jsonPath("$.sessions[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.sessions[0].source").value("cron"))
            .andExpect(jsonPath("$.sessions[0].title").value("Old cron"))
            .andExpect(jsonPath("$.sessions[0].message_count").value(2));
    }

    @Test
    void sessionPruneRemovesEndedCandidatesThroughSharedDeletePath() throws Exception {
        SessionEntity candidate = pruneCandidate(SESSION_ID, "Old chat", "cli", "complete", 1);
        when(sessionRepository.findPruneCandidates(
                eq("default"), any(Instant.class), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(false)))
            .thenReturn(List.of(candidate));
        when(sessionRepository.countOpenPruneMatches(
                eq("default"), any(Instant.class), isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), eq(false)))
            .thenReturn(2L);
        when(sessionRepository.findExistingIds(List.of(SESSION_ID))).thenReturn(List.of(SESSION_ID));

        mockMvc.perform(post("/api/sessions/prune")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"older_than_days\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.removed").value(1))
            .andExpect(jsonPath("$.skipped_open").value(2));

        verify(sessionRepository).orphanChildrenOf(List.of(SESSION_ID));
        verify(messageRepository).deleteBySessionIdIn(List.of(SESSION_ID));
        verify(sessionRepository).deleteAllByIdInBatch(List.of(SESSION_ID));
    }

    @Test
    void sessionPruneRejectsUnsafeOrUnsupportedFiltersExplicitly() throws Exception {
        mockMvc.perform(post("/api/sessions/prune")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"older_than_days\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("older_than_days must be >= 1"));

        mockMvc.perform(post("/api/sessions/prune")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cwd_prefix\":\"/tmp/project\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail")
                .value("session prune filter 'cwd_prefix' is not implemented in the Java port"));

        mockMvc.perform(post("/api/sessions/prune")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"min_messages\":\"nope\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("min_messages must be an integer"));
    }

    @Test
    void sessionOwnerBackfillStampsLegacyRowsWithResolvedProfileLikeHermes() throws Exception {
        when(sessionRepository.backfillBlankProfiles("default")).thenReturn(2);
        when(sessionRepository.backfillBlankProfiles("work")).thenReturn(1);

        mockMvc.perform(post("/api/sessions/owner-backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.stamped").value(2))
            .andExpect(jsonPath("$.profile").value("default"));

        mockMvc.perform(post("/p/work/api/sessions/owner-backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.stamped").value(1))
            .andExpect(jsonPath("$.profile").value("work"));

        verify(sessionRepository).backfillBlankProfiles("default");
        verify(sessionRepository).backfillBlankProfiles("work");
    }

    @Test
    void sessionOwnerBackfillRejectsInvalidBodiesAndProfileMismatch() throws Exception {
        mockMvc.perform(post("/api/sessions/owner-backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Request body must be a JSON object"));

        mockMvc.perform(post("/p/work/api/sessions/owner-backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profile\":\"default\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("profile_mismatch"));

        verify(sessionRepository, never()).backfillBlankProfiles("default");
    }

    @Test
    void createSessionUsesHermesCanonicalLocation() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "550e8400-e29b-41d4-a716-446655440000",
                      "user_id": "user-1",
                      "model": "gpt-test",
                      "title": "New chat",
                      "source": "api_server",
                      "system_prompt": "Answer tersely."
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/sessions/" + SESSION_ID))
            .andExpect(jsonPath("$.object").value("hermes.session"))
            .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.session.source").value("api_server"))
            .andExpect(jsonPath("$.session.profile").value("default"))
            .andExpect(jsonPath("$.session.is_default_profile").value(true))
            .andExpect(jsonPath("$.session.has_system_prompt").value(true));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getId()).isEqualTo(SESSION_ID);
        assertThat(entityCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(entityCaptor.getValue().getModelName()).isEqualTo("gpt-test");
        assertThat(entityCaptor.getValue().getSystemPrompt()).isEqualTo("Answer tersely.");
        assertThat(entityCaptor.getValue().getProfile()).isEqualTo("default");
    }

    @Test
    void createSessionUnderProfilePrefixPersistsProfileScope() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/p/work/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "550e8400-e29b-41d4-a716-446655440000",
                      "title": "Work chat"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.profile").value("work"))
            .andExpect(jsonPath("$.session.is_default_profile").value(false));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getProfile()).isEqualTo("work");
    }

    @Test
    void createSessionRejectsConflictingProfileSources() throws Exception {
        mockMvc.perform(post("/p/work/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "profile": "default",
                      "title": "Wrong scope"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("profile_mismatch"));
    }

    @Test
    void createSessionRespectsBrowserSourceAndModelLockMetadataLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "550e8400-e29b-41d4-a716-446655440000",
                      "source": "hermes_browser",
                      "provider": "nous",
                      "model": "x-ai/grok-4.5",
                      "require_model_lock": true,
                      "model_options": {
                        "reasoning": {"effort": "high"},
                        "fast": true,
                        "max_completion_tokens": 2048
                      },
                      "title": "Browser lock",
                      "system_prompt": "browser prompt"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.source").value("hermes_browser"))
            .andExpect(jsonPath("$.session.model").value("x-ai/grok-4.5"))
            .andExpect(jsonPath("$.session.has_model_config").value(true));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        SessionEntity entity = entityCaptor.getValue();
        assertThat(entity.getModelProvider()).isEqualTo("nous");
        assertThat(entity.getModelName()).isEqualTo("x-ai/grok-4.5");
        assertThat(entity.getCliStateValue("browserModelConfigPresent")).isEqualTo("true");
        assertThat(entity.getCliStateValue("browserModelLockConfirmed")).isEqualTo("true");
        assertThat(entity.getCliStateValue("browserModelLockRequestedProvider")).isEqualTo("nous");
        assertThat(entity.getCliStateValue("browserModelLockRequestedModel")).isEqualTo("x-ai/grok-4.5");
        assertThat(entity.getCliStateValue("browserModelLockRouteSource")).isEqualTo("raw_request");
        @SuppressWarnings("unchecked")
        Map<String, Object> storedOptions = new ObjectMapper().readValue(
            entity.getCliStateValue("browserModelLockModelOptions"),
            Map.class);
        assertThat(storedOptions).containsEntry("fast", true);
        assertThat(entity.getCliStateValue("reasoningEffort")).isEqualTo("high");
        assertThat(entity.getCliStateValue("fastMode")).isEqualTo("true");
        assertThat(entity.getCliStateValue("maxTokens")).isEqualTo("2048");
    }

    @Test
    void createSessionWithoutModelDoesNotPersistVirtualAliasLikeHermes() throws Exception {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            entity.setId(SESSION_ID);
            return entity;
        });

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.title").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.session.model").value(org.hamcrest.Matchers.nullValue()));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getTitle()).isNull();
        assertThat(entityCaptor.getValue().getModelName()).isEmpty();
    }

    @Test
    void createSessionIgnoresNonStringModelAndProviderLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "550e8400-e29b-41d4-a716-446655440000",
                      "model": {"id": "gpt-5"},
                      "provider": ["minimax"]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.model").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.session.has_model_config").value(false));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getModelName()).isEmpty();
        assertThat(entityCaptor.getValue().getModelProvider()).isEqualTo("openai-compatible");
        assertThat(entityCaptor.getValue().getCliStateValue("browserModelConfigPresent")).isNull();
    }

    @Test
    void createSessionWithExplicitVirtualAliasDoesNotPersistItLikeHermes() throws Exception {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            entity.setId(SESSION_ID);
            return entity;
        });

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model": "hermes-agent"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.model").value(org.hamcrest.Matchers.nullValue()));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getModelName()).isEmpty();
    }

    @Test
    void createSessionWithProviderPrefixedVirtualAliasDoesNotPersistItLikeHermes() throws Exception {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            entity.setId(SESSION_ID);
            return entity;
        });

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model": "openrouter::hermes-agent"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.model").value(org.hamcrest.Matchers.nullValue()));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getModelName()).isEmpty();
    }

    @Test
    void createSessionDoesNotSplitInvalidProviderPrefixedModelLikeHermes() throws Exception {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            entity.setId(SESSION_ID);
            return entity;
        });

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model": "bad provider::gpt-5"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.model").value("bad provider::gpt-5"))
            .andExpect(jsonPath("$.session.has_model_config").value(true));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getModelName()).isEqualTo("bad provider::gpt-5");
        assertThat(entityCaptor.getValue().getModelProvider()).isEqualTo("openai-compatible");
        assertThat(entityCaptor.getValue().getCliStateValue("browserModelLockRequestedProvider")).isNull();
        assertThat(entityCaptor.getValue().getCliStateValue("browserModelLockRequestedModel"))
            .isEqualTo("bad provider::gpt-5");
    }

    @Test
    void createSessionRejectsDuplicateRequestedId() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"session_id": "550e8400-e29b-41d4-a716-446655440000"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.message").value("Session already exists: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_exists"));
    }

    @Test
    void createSessionRejectsInvalidRequestedIdWithHermesError() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"session_id": "../bad"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid session ID"))
            .andExpect(jsonPath("$.error.code").value("invalid_session_id"));
    }

    @Test
    void createSessionRejectsMalformedJsonLikeHermes() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid JSON in request body"))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"));

        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void createSessionRejectsNonObjectJsonLikeHermes() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Request body must be a JSON object"))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"));

        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void createSessionRejectsInvalidSystemPromptType() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"system_prompt": {"text": "nope"}}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("system_prompt must be a string"))
            .andExpect(jsonPath("$.error.code").value("invalid_system_prompt"));
    }

    @Test
    void createSessionNormalizesBrowserSourceLikeHermes() throws Exception {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            entity.setId(SESSION_ID);
            return entity;
        });

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"source": "browser"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.source").value("hermes_browser"));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getSource()).isEqualTo("hermes_browser");
    }

    @Test
    void createSessionSanitizesTitleLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id": "550e8400-e29b-41d4-a716-446655440000",
                      "title": "  Hello\\n\\twide\\b   world  "
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session.title").value("Hello wide world"));

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getTitle()).isEqualTo("Hello wide world");
    }

    @Test
    void createSessionRejectsTooLongTitleLikeHermes() throws Exception {
        String title = "A".repeat(101);

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "%s"
                    }
                    """.formatted(title)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Title too long (101 chars, max 100)"))
            .andExpect(jsonPath("$.error.code").value("invalid_title"));

        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void createSessionRejectsDuplicateTitleLikeHermes() throws Exception {
        SessionEntity conflict = sessionEntity("Taken");
        conflict.setId(FORK_ID);
        when(sessionRepository.findByTitle("Taken")).thenReturn(conflict);

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "Taken"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Title already in use by session " + FORK_ID))
            .andExpect(jsonPath("$.error.code").value("invalid_title"));

        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void getMissingSessionReturnsHermesErrorEnvelope() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));
    }

    @Test
    void getSessionAlwaysIncludesHasModelConfigFlagLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Plain");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.profile").value("default"))
            .andExpect(jsonPath("$.is_default_profile").value(true))
            .andExpect(jsonPath("$.has_model_config").value(false))
            .andExpect(jsonPath("$.session.has_model_config").value(false));

        entity.setCliStateValue("browserModelConfigPresent", "true");
        mockMvc.perform(get("/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.has_model_config").value(true))
            .andExpect(jsonPath("$.session.has_model_config").value(true));
    }

    @Test
    void profilePrefixedGetSessionRouteIsScopedToOwningProfile() throws Exception {
        SessionEntity entity = sessionEntity("Plain");
        entity.setProfile("work");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/p/work/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.is_default_profile").value(false))
            .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()));

        entity.setProfile("default");
        mockMvc.perform(get("/p/work/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID));
    }

    @Test
    void invalidSessionIdPathReturnsHermesErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/sessions/api-session"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid session ID"))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.code").value("invalid_session_id"));
    }

    @Test
    void deleteMissingSessionIsIdempotentLikeHermes() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.already_absent").value(true));
    }

    @Test
    void deleteSessionBulkDeletesMessagesWithoutLoadingTranscriptLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Doomed");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        mockMvc.perform(delete("/api/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.session.deleted"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.deleted").value(true));

        verify(sessionRepository).orphanChildrenOf(List.of(SESSION_ID));
        verify(messageRepository).deleteBySessionId(SESSION_ID);
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(SESSION_ID);
        verify(messageRepository, never()).deleteAll(any());
        verify(sessionRepository).delete(entity);
    }

    @Test
    void latestDescendantReturnsFullNewestChildPathLikeHermes() throws Exception {
        SessionEntity root = sessionEntity("Root");
        SessionEntity child = sessionEntity("Child");
        child.setId(FORK_ID);
        child.setParentSessionId(SESSION_ID);
        child.setCreatedAt(BASE_TIME.plusSeconds(1));
        SessionEntity leaf = sessionEntity("Leaf");
        leaf.setId(LEAF_ID);
        leaf.setParentSessionId(FORK_ID);
        leaf.setCreatedAt(BASE_TIME.plusSeconds(2));

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(root));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(SESSION_ID)).thenReturn(List.of(child));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(FORK_ID)).thenReturn(List.of(leaf));
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(LEAF_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/{sessionId}/latest-descendant", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requested_session_id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.session_id").value(LEAF_ID.toString()))
            .andExpect(jsonPath("$.path[0]").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.path[1]").value(FORK_ID.toString()))
            .andExpect(jsonPath("$.path[2]").value(LEAF_ID.toString()))
            .andExpect(jsonPath("$.changed").value(true));

        verify(sessionResolver, never()).resolveResumeSessionId(SESSION_ID);
    }

    @Test
    void exportSessionReturnsMetadataAndActiveMessagesLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Export me");
        MessageEntity message = message(1);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(message));

        mockMvc.perform(get("/api/sessions/{sessionId}/export", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.title").value("Export me"))
            .andExpect(jsonPath("$.messages[0].content").value("msg 1"));
    }

    @Test
    void patchSessionPersistsHermesVisibilityFlags() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(entity)).thenReturn(entity);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "After",
                      "end_reason": "branched",
                      "pinned": true,
                      "archived": true,
                      "hidden": true,
                      "unread": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.session"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.title").value("After"))
            .andExpect(jsonPath("$.pinned").value(true))
            .andExpect(jsonPath("$.archived").value(true))
            .andExpect(jsonPath("$.hidden").value(true))
            .andExpect(jsonPath("$.unread").value(true))
            .andExpect(jsonPath("$.session.title").value("After"))
            .andExpect(jsonPath("$.session.end_reason").value("branched"))
            .andExpect(jsonPath("$.session.pinned").value(true))
            .andExpect(jsonPath("$.session.archived").value(true))
            .andExpect(jsonPath("$.session.hidden").value(true))
            .andExpect(jsonPath("$.session.unread").doesNotExist());

        assertThat(entity.getTitle()).isEqualTo("After");
        assertThat(entity.getEndReason()).isEqualTo("branched");
        assertThat(entity.getPinned()).isTrue();
        assertThat(entity.getArchived()).isTrue();
        assertThat(entity.getHidden()).isTrue();
        assertThat(entity.getUnread()).isTrue();
    }

    @Test
    void patchSessionIgnoresFalsyEndReasonLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        entity.setEndReason("existing");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(entity)).thenReturn(entity);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "end_reason": ""
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.end_reason").value("existing"));

        assertThat(entity.getEndReason()).isEqualTo("existing");
    }

    @Test
    void patchSessionRejectsUnknownFields() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity("Before")));

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "After", "surprise": true}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Unsupported session fields: surprise"))
            .andExpect(jsonPath("$.error.code").value("unsupported_session_field"));
    }

    @Test
    void patchSessionRejectsNonBooleanFlags() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity("Before")));

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"pinned": "yes"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("'pinned' must be a boolean"))
            .andExpect(jsonPath("$.error.code").value("invalid_session_field"));
    }

    @Test
    void patchSessionCoercesTitleToStringLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(entity)).thenReturn(entity);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": 123}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.title").value("123"));

        assertThat(entity.getTitle()).isEqualTo("123");
    }

    @Test
    void patchSessionSanitizesBlankTitleToNullLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(entity)).thenReturn(entity);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "  \\n\\t  "}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session.title").value(org.hamcrest.Matchers.nullValue()));

        assertThat(entity.getTitle()).isNull();
    }

    @Test
    void patchSessionRejectsDuplicateTitleLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        SessionEntity conflict = sessionEntity("After");
        conflict.setId(FORK_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.findByTitle("After")).thenReturn(conflict);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "After"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value(
                "Title 'After' is already in use by session " + FORK_ID))
            .andExpect(jsonPath("$.error.code").value("invalid_title"));

        assertThat(entity.getTitle()).isEqualTo("Before");
        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void patchSessionRejectsTooLongTitleLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Before");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        String title = "A".repeat(101);

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "%s"}
                    """.formatted(title)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Title too long (101 chars, max 100)"))
            .andExpect(jsonPath("$.error.code").value("invalid_title"));

        assertThat(entity.getTitle()).isEqualTo("Before");
        verify(sessionRepository, never()).save(entity);
    }

    @Test
    void patchMissingSessionReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));
    }

    @Test
    void patchSessionRejectsNonObjectJsonLikeHermes() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionEntity("Before")));

        mockMvc.perform(patch("/api/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Request body must be a JSON object"));
    }

    @Test
    void sessionMessagesDefaultToLatestBoundedPage() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtDesc(SESSION_ID, 500, 0))
            .thenReturn(messagesDescending(500, 1));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.limit").value(500))
            .andExpect(jsonPath("$.pagination.offset").value(0))
            .andExpect(jsonPath("$.pagination.order").value("latest"))
            .andExpect(jsonPath("$.pagination.returned").value(500))
            .andExpect(jsonPath("$.data.length()").value(500))
            .andExpect(jsonPath("$.data[0].content").value("msg 1"))
            .andExpect(jsonPath("$.data[0].timestamp").value(BASE_TIME.plusSeconds(1).getEpochSecond()))
            .andExpect(jsonPath("$.data[499].content").value("msg 500"));
    }

    @Test
    void sessionMessagesResolveResumeSessionIdLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionResolver.resolveResumeSessionId(SESSION_ID)).thenReturn(FORK_ID);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtDesc(FORK_ID, 500, 0))
            .thenReturn(messagesDescending(2, 1));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value(FORK_ID.toString()))
            .andExpect(jsonPath("$.data[0].content").value("msg 1"))
            .andExpect(jsonPath("$.data[1].content").value("msg 2"));
    }

    @Test
    void sessionMessagesIncludeCompactedSurfacesArchivedRowsLikeHermes() throws Exception {
        MessageEntity oldQ = messageWithContent(1, "old q", false, true);
        MessageEntity oldA = messageWithContent(2, "old a", false, true);
        MessageEntity summary = messageWithContent(3, "summary", true, false);
        MessageEntity liveQ = messageWithContent(4, "live q", true, false);
        MessageEntity liveA = messageWithContent(5, "live a", true, false);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findDisplayBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(oldQ, oldA, summary, liveQ, liveA));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("include_compacted", "true")
                .param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.include_compacted").value(true))
            .andExpect(jsonPath("$.data[0].content").value("old q"))
            .andExpect(jsonPath("$.messages[0].content").value("old q"))
            .andExpect(jsonPath("$.data[1].content").value("old a"))
            .andExpect(jsonPath("$.data[2].content").value("summary"))
            .andExpect(jsonPath("$.data[3].content").value("live q"))
            .andExpect(jsonPath("$.data[4].content").value("live a"));
    }

    @Test
    void sessionMessagesIncludeCompactedDedupesAndPagesLatestLikeHermes() throws Exception {
        MessageEntity archivedLiveQ = messageWithContent(4, "live q", false, true);
        MessageEntity liveQ = messageWithContent(40, "live q", true, false);
        archivedLiveQ.setTurnIndex(7);
        liveQ.setTurnIndex(7);
        liveQ.setCreatedAt(archivedLiveQ.getCreatedAt().plusMillis(1));
        MessageEntity oldQ = messageWithContent(1, "old q", false, true);
        MessageEntity oldA = messageWithContent(2, "old a", false, true);
        MessageEntity summary = messageWithContent(3, "summary", true, false);
        MessageEntity liveA = messageWithContent(5, "live a", true, false);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findDisplayBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(oldQ, oldA, summary, archivedLiveQ, liveQ, liveA));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("include_compacted", "true")
                .param("limit", "2")
                .param("offset", "1")
                .param("order", "latest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.returned").value(2))
            .andExpect(jsonPath("$.data[0].content").value("summary"))
            .andExpect(jsonPath("$.data[1].id").value(liveQ.getId().toString()))
            .andExpect(jsonPath("$.data[1].content").value("live q"));
    }

    @Test
    void sessionMessagesHonorOffsetThatIsNotPageAligned() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtAsc(SESSION_ID, 2, 1))
            .thenReturn(messagesAscending(1, 3));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "2")
                .param("offset", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.order").value("oldest"))
            .andExpect(jsonPath("$.data[0].content").value("msg 1"))
            .andExpect(jsonPath("$.data[1].content").value("msg 2"));
    }

    @Test
    void sessionMessagesExposeAssistantToolCalls() throws Exception {
        MessageEntity assistant = message(1);
        assistant.setRole("assistant");
        assistant.setContent("");
        assistant.setToolCallId("call_1");
        assistant.setToolCallName("web_search");
        assistant.setToolCallArguments("{\"query\":\"java\"}");
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtAsc(SESSION_ID, 1, 0))
            .thenReturn(List.of(assistant));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].turn_index").doesNotExist())
            .andExpect(jsonPath("$.data[0].tool_calls[0].id").value("call_1"))
            .andExpect(jsonPath("$.data[0].tool_calls[0].type").value("function"))
            .andExpect(jsonPath("$.data[0].tool_calls[0].function.name").value("web_search"))
            .andExpect(jsonPath("$.data[0].tool_calls[0].function.arguments").value("{\"query\":\"java\"}"));
    }

    @Test
    void sessionMessagesExposeStoredAssistantToolCallsArray() throws Exception {
        MessageEntity assistant = message(1);
        assistant.setRole("assistant");
        assistant.setContent("");
        assistant.setToolCallId("call_1");
        assistant.setToolCallName("web_search");
        assistant.setToolCallArguments("{\"query\":\"java\"}");
        assistant.setToolCalls("""
            [
              {"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"java\\"}"}},
              {"id":"call_2","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}
            ]
            """);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtAsc(SESSION_ID, 1, 0))
            .thenReturn(List.of(assistant));

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].tool_calls[0].id").value("call_1"))
            .andExpect(jsonPath("$.data[0].tool_calls[1].id").value("call_2"))
            .andExpect(jsonPath("$.data[0].tool_calls[1].function.name").value("read_file"))
            .andExpect(jsonPath("$.data[0].tool_calls[1].function.arguments").value("{\"path\":\"README.md\"}"));
    }

    @Test
    void sessionMessagesRejectInvalidOrder() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("order", "middle"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("order must be one of: oldest, latest"))
            .andExpect(jsonPath("$.error.code").value("invalid_pagination"));
    }

    @Test
    void sessionMessagesRejectInvalidOffsetWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("offset", "nope"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("limit and offset must be non-negative integers"))
            .andExpect(jsonPath("$.error.code").value("invalid_pagination"));
    }

    @Test
    void sessionMessagesRejectNegativeLimitWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("limit and offset must be non-negative integers"))
            .andExpect(jsonPath("$.error.code").value("invalid_pagination"));
    }

    @Test
    void sessionMessagesCapsOversizedLimitLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(messageRepository.findActivePageBySessionIdOrderByCreatedAtAsc(SESSION_ID, 500, 0))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pagination.limit").value(500));

        verify(messageRepository).findActivePageBySessionIdOrderByCreatedAtAsc(SESSION_ID, 500, 0);
    }

    @Test
    void forkSessionExposesHermesRouteAndEnvelope() throws Exception {
        SessionSummaryDto fork = new SessionSummaryDto(
            FORK_ID,
            "user-1",
            "Fork title",
            "openai-compatible",
            "gpt-test",
            BASE_TIME,
            BASE_TIME,
            SESSION_ID
        );
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.branchSession(SESSION_ID, "Fork title")).thenReturn(fork);

        mockMvc.perform(post("/api/sessions/{sessionId}/fork", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title": "Fork title"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/sessions/" + FORK_ID))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.object").value("hermes.session"))
            .andExpect(jsonPath("$.session.id").value(FORK_ID.toString()))
            .andExpect(jsonPath("$.session.parent_session_id").value(SESSION_ID.toString()));
    }

    @Test
    void forkSessionAcceptsRequestedUuidSessionIdLikeHermes() throws Exception {
        SessionSummaryDto fork = new SessionSummaryDto(
            FORK_ID,
            "user-1",
            "Fork title",
            "openai-compatible",
            "gpt-test",
            BASE_TIME,
            BASE_TIME,
            SESSION_ID
        );
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionRepository.existsById(FORK_ID)).thenReturn(false);
        when(agentRuntimeService.branchSession(SESSION_ID, FORK_ID, "Fork title")).thenReturn(fork);

        mockMvc.perform(post("/api/sessions/{sessionId}/fork", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"session_id": "%s", "title": "Fork title"}
                    """.formatted(FORK_ID)))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/sessions/" + FORK_ID))
            .andExpect(jsonPath("$.session.id").value(FORK_ID.toString()));
    }

    @Test
    void forkSessionRejectsInvalidRequestedIdWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/fork", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id": "api_fork"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid session ID"))
            .andExpect(jsonPath("$.error.code").value("invalid_session_id"));

        verify(agentRuntimeService, never()).branchSession(eq(SESSION_ID), any(UUID.class), any());
    }

    @Test
    void forkMissingSessionReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/sessions/{sessionId}/fork", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));

        verify(agentRuntimeService, never()).branchSession(eq(SESSION_ID), any());
    }

    @Test
    void forkSessionRejectsDuplicateRequestedIdWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionRepository.existsById(FORK_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/fork", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"id": "%s"}
                    """.formatted(FORK_ID)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.message").value("Session already exists: " + FORK_ID))
            .andExpect(jsonPath("$.error.code").value("session_exists"));

        verify(agentRuntimeService, never()).branchSession(eq(SESSION_ID), any(UUID.class), any());
    }

    @Test
    void lockSessionModelAcceptsHermesModelLockShape() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model_id": "openai-compatible::gpt-5",
                      "model_options": {
                        "reasoning_effort": "high"
                      },
                      "fast_mode": true,
                      "max_completion_tokens": 2048
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.session.model_lock"))
            .andExpect(jsonPath("$.session_id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.runtime.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.runtime.model").value("gpt-5"))
            .andExpect(jsonPath("$.runtime.model_lock").value("accepted"))
            .andExpect(jsonPath("$.runtime.model_options.reasoning_effort").value("high"))
            .andExpect(jsonPath("$.runtime.model_options.fast_mode").value(true))
            .andExpect(jsonPath("$.runtime.model_options.max_completion_tokens").value(2048));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentRuntimeService)
            .switchModel(eq(SESSION_ID), eq("gpt-5"), eq("openai-compatible"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).containsEntry("reasoning_effort", "high");
        assertThat(optionsCaptor.getValue()).containsEntry("fast_mode", true);
        assertThat(optionsCaptor.getValue()).containsEntry("max_completion_tokens", 2048);
    }

    @Test
    void profilePrefixedSessionModelLockRouteMirrorsHermesMultiplexAlias() throws Exception {
        SessionEntity entity = sessionEntity("Work");
        entity.setProfile("work");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/p/work/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model_id": "openai-compatible::gpt-5"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.session.model_lock"))
            .andExpect(jsonPath("$.session_id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.runtime.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.runtime.model").value("gpt-5"));
    }

    @Test
    void lockSessionModelMissingSessionReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
    }

    @Test
    void lockSessionModelIgnoresNonObjectModelOptionsLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model_id": "openai-compatible::gpt-5",
                      "model_options": "ignored"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtime.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.runtime.model").value("gpt-5"))
            .andExpect(jsonPath("$.runtime.model_options").doesNotExist());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentRuntimeService)
            .switchModel(eq(SESSION_ID), eq("gpt-5"), eq("openai-compatible"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).isEmpty();
    }

    @Test
    void lockSessionModelResolvesConfiguredAliasLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/session-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("session-fast", route);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "session-fast"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtime.provider").value("openrouter"))
            .andExpect(jsonPath("$.runtime.model").value("openrouter/session-fast"))
            .andExpect(jsonPath("$.runtime.route_source").value("model_routes"))
            .andExpect(jsonPath("$.runtime.requested.model").value("session-fast"))
            .andExpect(jsonPath("$.runtime.model_lock").value("accepted"));

        verify(agentRuntimeService).switchModel(eq(SESSION_ID), eq("session-fast"), isNull(), eq(Map.of()));
    }

    @Test
    void lockSessionModelRejectsMissingModel() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message")
                .value("require_model_lock was set but no model/provider was provided"))
            .andExpect(jsonPath("$.error.code").value("missing_model"));

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
    }

    @Test
    void lockSessionModelRejectsProviderOnlyGlobalFallback() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"provider": "openai-compatible"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.message")
                .value("Requested Browser model lock cannot be routed; refusing silent global fallback"))
            .andExpect(jsonPath("$.error.code").value("model_lock_unavailable"));

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
    }

    @Test
    void lockSessionModelRejectsInvalidRuntimeIdWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/model", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model_id": "bad\\nmodel"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("model contains invalid characters"))
            .andExpect(jsonPath("$.error.code").value("invalid_model_lock"));

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
    }

    @Test
    void sessionChatAcceptsInputAliasTextPartsAndEchoesSessionHeaders() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true, false, "gpt-test", 9, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "desktop-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {"type": "input_text", "text": "one"},
                        {"type": "text", "text": "two"}
                      ],
                      "timeoutMs": 123
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(header().string(OpenAiSessionService.SESSION_KEY_HEADER, "desktop-key"))
            .andExpect(jsonPath("$.object").value("hermes.session.chat.completion"))
            .andExpect(jsonPath("$.session_id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.message.role").value("assistant"))
            .andExpect(jsonPath("$.message.content").value("done"))
            .andExpect(jsonPath("$.usage.input_tokens").value(9))
            .andExpect(jsonPath("$.usage.output_tokens").value(0))
            .andExpect(jsonPath("$.usage.total_tokens").value(9))
            .andExpect(jsonPath("$.runtime.model").value("gpt-test"))
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.content").value("done"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().message()).isEqualTo("one\ntwo");
        assertThat(requestCaptor.getValue().timeoutMs()).isEqualTo(123L);
    }

    @Test
    void sessionChatReturns429WhenApiRunLimitIsReachedLikeHermes() throws Exception {
        apiProperties.setMaxConcurrentRuns(1);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"input": "hello"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error.message").value("Too many concurrent runs (max 1)"))
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatDoesNotPersistModelLockWhenApiRunLimitIsReached() throws Exception {
        apiProperties.setMaxConcurrentRuns(1);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "input": "hello",
                          "model_id": "openai-compatible::gpt-5",
                          "require_model_lock": true
                        }
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatSessionKeyRequiresConfiguredApiKeyLikeHermes() throws Exception {
        AgentProperties.SecurityProperties securityProperties = new AgentProperties.SecurityProperties();
        securityProperties.setApiKey("");
        when(properties.getSecurity()).thenReturn(securityProperties);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "desktop-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"input": "hello"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.message").value("X-Hermes-Session-Key requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature."))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.error.code").value(org.hamcrest.Matchers.nullValue()));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatRejectsMissingMessageWithHermesError() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Missing 'message' field"))
            .andExpect(jsonPath("$.error.code").value("missing_message"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatMissingSessionReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatRejectsNonObjectJsonLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Request body must be a JSON object"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatDoesNotFallBackFromTruthyInvisibleMessageToInputLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "message": [{"type": "refusal", "refusal": "no"}],
                      "input": "fallback text"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Missing 'message' field"))
            .andExpect(jsonPath("$.error.code").value("missing_message"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatRejectsInvalidImageWithHermesErrorShape() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message": [{"type": "input_image", "image_url": ""}]}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Image parts must include a non-empty image URL."))
            .andExpect(jsonPath("$.error.param").value("message"))
            .andExpect(jsonPath("$.error.code").value("invalid_image_url"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatPassesRequestModelAndModelOptionsToRuntimeLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "ok", List.of(), true, false, "MiniMax-M3", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model": "MiniMax-M3",
                      "provider": "minimax",
                      "model_options": {
                        "reasoning_effort": "medium",
                        "service_tier": "priority",
                        "voice": true,
                        "personality": "concise",
                        "subgoal": "summarize",
                        "max_completion_tokens": 555
                      },
                      "system_message": "Stay in session scope."
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("ok"))
            .andExpect(jsonPath("$.runtime.model").value("MiniMax-M3"))
            .andExpect(jsonPath("$.runtime.provider").value("minimax"))
            .andExpect(jsonPath("$.runtime.model_options.max_completion_tokens").value(555));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.model()).isEqualTo("MiniMax-M3");
        assertThat(request.provider()).isEqualTo("minimax");
        assertThat(request.reasoningEffort()).isEqualTo("medium");
        assertThat(request.fastMode()).isNull();
        assertThat(request.voiceMode()).isTrue();
        assertThat(request.personality()).isEqualTo("concise");
        assertThat(request.subgoal()).isEqualTo("summarize");
        assertThat(request.maxCompletionTokens()).isEqualTo(555);
        assertThat(request.systemPromptOverride()).isEqualTo("Stay in session scope.");
        assertThat(request.serviceTier()).isEqualTo("priority");
    }

    @Test
    void sessionChatRoutesConfiguredModelAliasWithTransportLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/session-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("session-fast", route);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "ok", List.of(), true, false, "openrouter/session-fast", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model": "session-fast"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("ok"))
            .andExpect(jsonPath("$.runtime.model").value("openrouter/session-fast"))
            .andExpect(jsonPath("$.runtime.provider").value("openrouter"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.model()).isEqualTo("openrouter/session-fast");
        assertThat(request.provider()).isEqualTo("openrouter");
        assertThat(request.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(request.apiKey()).isEqualTo("sk-route-secret");
    }

    @Test
    void sessionChatResolvesStoredModelRouteAliasLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/session-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("session-fast", route);
        SessionEntity entity = sessionEntity("Route pinned");
        entity.setModelName("session-fast");
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "ok", List.of(), true, false, "openrouter/session-fast", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "message": "hello",
                      "model_options": {
                        "service_tier": "priority"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("ok"))
            .andExpect(jsonPath("$.runtime.model").value("openrouter/session-fast"))
            .andExpect(jsonPath("$.runtime.provider").value("openrouter"))
            .andExpect(jsonPath("$.runtime.route_source").value("model_routes"))
            .andExpect(jsonPath("$.runtime.model_options.service_tier").value("priority"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        ChatRequest request = requestCaptor.getValue();
        assertThat(request.model()).isEqualTo("openrouter/session-fast");
        assertThat(request.provider()).isEqualTo("openrouter");
        assertThat(request.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(request.apiKey()).isEqualTo("sk-route-secret");
        assertThat(request.serviceTier()).isEqualTo("priority");
    }

    @Test
    void sessionChatIgnoresNonStringModelAndProviderLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "ok", List.of(), true, false, "gpt-test", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model": {"id": "gpt-5"},
                      "provider": ["minimax"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("ok"))
            .andExpect(jsonPath("$.runtime.model").value("gpt-test"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().model()).isNull();
        assertThat(requestCaptor.getValue().provider()).isNull();
    }

    @Test
    void sessionChatRejectsProviderConflictAgainstStoredRouteAliasLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/session-fast");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("session-fast", route);
        SessionEntity entity = new SessionEntity();
        entity.setId(SESSION_ID);
        entity.setUserId(AgentProperties.DEFAULT_USER_ID);
        entity.setModelProvider("openai-compatible");
        entity.setModelName("session-fast");
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "provider": "minimax"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_model_selection"))
            .andExpect(jsonPath("$.error.message")
                .value("Model route 'session-fast' is pinned to provider 'openrouter'. Remove 'provider' or use 'openrouter'."));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatUsesEffectiveResponseSessionIdInHermesEnvelopeAndHeader() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(FORK_ID, "moved", List.of(), true));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"input": "hello"}
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, FORK_ID.toString()))
            .andExpect(jsonPath("$.session_id").value(FORK_ID.toString()))
            .andExpect(jsonPath("$.sessionId").value(FORK_ID.toString()))
            .andExpect(jsonPath("$.requestedSessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.message.content").value("moved"));
    }

    @Test
    void sessionChatRejectsInvalidSystemMessageLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "system_message": {"text": "not allowed"}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("system_message must be a string"))
            .andExpect(jsonPath("$.error.code").value("invalid_system_message"));

        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatFallsBackFromFalsySystemMessageToInstructionsLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "ok", List.of(), true));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "system_message": "",
                      "instructions": "Use fallback instructions."
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("ok"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().systemPromptOverride()).isEqualTo("Use fallback instructions.");
    }

    @Test
    void sessionChatRequireModelLockRejectsProviderOnlyGlobalFallback() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "provider": "openai-compatible",
                      "require_model_lock": "on"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.message")
                .value("Requested Browser model lock cannot be routed; refusing silent global fallback"))
            .andExpect(jsonPath("$.error.code").value("model_lock_unavailable"));

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
        verify(agentRuntimeService, never()).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatRequireModelLockPersistsModelBeforeTurn() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "locked", List.of(), true));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model_id": "openai-compatible::gpt-5",
                      "require_model_lock": true,
                      "model_options": {
                        "reasoning_effort": "high"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.session.chat.completion"))
            .andExpect(jsonPath("$.message.content").value("locked"))
            .andExpect(jsonPath("$.content").value("locked"))
            .andExpect(jsonPath("$.runtime.provider").value("openai-compatible"))
            .andExpect(jsonPath("$.runtime.model").value("gpt-5"))
            .andExpect(jsonPath("$.runtime.model_lock").value("accepted"))
            .andExpect(jsonPath("$.runtime.model_options.reasoning_effort").value("high"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentRuntimeService)
            .switchModel(eq(SESSION_ID), eq("gpt-5"), eq("openai-compatible"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).containsEntry("reasoning_effort", "high");
        verify(agentRuntimeService).runTurn(any(ChatRequest.class));
    }

    @Test
    void sessionChatRequireModelLockResolvesConfiguredAliasLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/session-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("session-fast", route);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "locked", List.of(), true, false, "openrouter/session-fast", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model": "session-fast",
                      "require_model_lock": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtime.provider").value("openrouter"))
            .andExpect(jsonPath("$.runtime.model").value("openrouter/session-fast"))
            .andExpect(jsonPath("$.runtime.route_source").value("model_routes"))
            .andExpect(jsonPath("$.runtime.requested.model").value("session-fast"))
            .andExpect(jsonPath("$.runtime.model_lock").value("accepted"));

        verify(agentRuntimeService).switchModel(eq(SESSION_ID), eq("session-fast"), isNull(), eq(Map.of()));
        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agentRuntimeService).runTurn(requestCaptor.capture());
        assertThat(requestCaptor.getValue().model()).isEqualTo("openrouter/session-fast");
        assertThat(requestCaptor.getValue().provider()).isEqualTo("openrouter");
        assertThat(requestCaptor.getValue().baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(requestCaptor.getValue().apiKey()).isEqualTo("sk-route-secret");
    }

    @Test
    void sessionChatReusesPersistedModelLockRuntimeMetadataLikeHermes() throws Exception {
        SessionEntity entity = sessionEntity("Locked");
        entity.setModelProvider("nous");
        entity.setModelName("x-ai/grok-4.5");
        entity.setCliStateValue("browserModelConfigPresent", "true");
        entity.setCliStateValue("browserModelLockConfirmed", "true");
        entity.setCliStateValue("browserModelLockRequestedProvider", "nous");
        entity.setCliStateValue("browserModelLockRequestedModel", "x-ai/grok-4.5");
        entity.setCliStateValue("browserModelLockRuntimeProvider", "nous");
        entity.setCliStateValue("browserModelLockRuntimeModel", "x-ai/grok-4.5");
        entity.setCliStateValue("browserModelLockRouteSource", "raw_request");
        entity.setCliStateValue("browserModelLockModelOptions",
            "{\"reasoning_effort\":\"high\",\"fast\":true}");
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "locked", List.of(), true, false, "x-ai/grok-4.5", 3, 1000));

        mockMvc.perform(post("/api/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"input": "use the stored lock"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message.content").value("locked"))
            .andExpect(jsonPath("$.runtime.provider").value("nous"))
            .andExpect(jsonPath("$.runtime.model").value("x-ai/grok-4.5"))
            .andExpect(jsonPath("$.runtime.requested.provider").value("nous"))
            .andExpect(jsonPath("$.runtime.requested.model").value("x-ai/grok-4.5"))
            .andExpect(jsonPath("$.runtime.route_source").value("session_model_lock"))
            .andExpect(jsonPath("$.runtime.model_options.reasoning_effort").value("high"))
            .andExpect(jsonPath("$.runtime.model_options.fast").value(true))
            .andExpect(jsonPath("$.runtime.model_lock").value("confirmed"));
    }

    @Test
    void sessionChatStreamSessionKeyRequiresConfiguredApiKeyLikeHermes() throws Exception {
        AgentProperties.SecurityProperties securityProperties = new AgentProperties.SecurityProperties();
        securityProperties.setApiKey("");
        when(properties.getSecurity()).thenReturn(securityProperties);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat/stream", SESSION_ID)
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "desktop-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"input": "hello"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.message").value("X-Hermes-Session-Key requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature."))
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.error.code").value(org.hamcrest.Matchers.nullValue()));

        verify(streamingService, never())
            .streamTurn(
                any(ChatRequest.class),
                any(UUID.class),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                any(Runnable.class));
    }

    @Test
    void sessionChatStreamDoesNotPersistModelLockWhenApiRunLimitIsReached() throws Exception {
        apiProperties.setMaxConcurrentRuns(1);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/api/sessions/{sessionId}/chat/stream", SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "input": "hello",
                          "model_id": "openai-compatible::gpt-5",
                          "require_model_lock": true
                        }
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(agentRuntimeService, never()).switchModel(any(UUID.class), any(), any(), any());
        verify(streamingService, never())
            .streamTurn(
                any(ChatRequest.class),
                any(UUID.class),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                any(Runnable.class));
    }

    @Test
    void sessionChatStreamMissingSessionReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/sessions/{sessionId}/chat/stream", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Session not found: " + SESSION_ID))
            .andExpect(jsonPath("$.error.code").value("session_not_found"));

        verify(streamingService, never())
            .streamTurn(
                any(ChatRequest.class),
                any(UUID.class),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                any(Runnable.class));
    }

    @Test
    void sessionChatStreamReturns429WhenApiRunLimitIsReachedLikeHermes() throws Exception {
        apiProperties.setMaxConcurrentRuns(1);
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/api/sessions/{sessionId}/chat/stream", SESSION_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"input": "hello"}
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error.message").value("Too many concurrent runs (max 1)"))
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(streamingService, never())
            .streamTurn(
                any(ChatRequest.class),
                any(UUID.class),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                any(Runnable.class));
    }

    @Test
    void sessionChatStreamUsesHermesStreamServiceAndRuntimeMetadata() throws Exception {
        when(sessionRepository.existsById(SESSION_ID)).thenReturn(true);
        when(streamingService.streamTurn(
                any(ChatRequest.class),
                eq(SESSION_ID),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                any(Runnable.class)))
            .thenReturn(new SseEmitter());

        mockMvc.perform(post("/api/sessions/{sessionId}/chat/stream", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "model_id": "openai-compatible::gpt-5",
                      "require_model_lock": true,
                      "model_options": {
                        "reasoning_effort": "high"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"));

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> runtimeCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamingService).streamTurn(
            requestCaptor.capture(),
            eq(SESSION_ID),
            runtimeCaptor.capture(),
            any(Runnable.class));
        assertThat(requestCaptor.getValue().message()).isEqualTo("hello");
        assertThat(runtimeCaptor.getValue()).containsEntry("provider", "openai-compatible");
        assertThat(runtimeCaptor.getValue()).containsEntry("model", "gpt-5");
        assertThat(runtimeCaptor.getValue()).containsEntry("model_lock", "accepted");
    }

    private static List<MessageEntity> messagesAscending(int startInclusive, int endExclusive) {
        return IntStream.range(startInclusive, endExclusive)
            .mapToObj(SessionCrudControllerTest::message)
            .toList();
    }

    private static List<MessageEntity> messagesDescending(int startInclusive, int endInclusive) {
        return IntStream.iterate(startInclusive, value -> value >= endInclusive, value -> value - 1)
            .mapToObj(SessionCrudControllerTest::message)
            .toList();
    }

    private static MessageEntity message(int index) {
        MessageEntity entity = new MessageEntity();
        entity.setId(UUID.nameUUIDFromBytes(("message-" + index).getBytes(StandardCharsets.UTF_8)));
        entity.setSessionId(SESSION_ID);
        entity.setRole("user");
        entity.setContent("msg " + index);
        entity.setCreatedAt(BASE_TIME.plusSeconds(index));
        return entity;
    }

    private static MessageEntity messageWithContent(int index, String content, boolean active, boolean compacted) {
        MessageEntity entity = message(index);
        entity.setContent(content);
        entity.setActive(active);
        entity.setCompacted(compacted);
        return entity;
    }

    private static SessionEntity sessionEntity(String title) {
        SessionEntity entity = new SessionEntity();
        entity.setId(SESSION_ID);
        entity.setUserId("user-1");
        entity.setTitle(title);
        entity.setModelProvider("openai-compatible");
        entity.setModelName("gpt-test");
        entity.setSource("api_server");
        entity.setCreatedAt(BASE_TIME.minusSeconds(60));
        entity.setUpdatedAt(BASE_TIME.minusSeconds(30));
        entity.setLastActive(BASE_TIME);
        entity.setMessageCount(2);
        entity.setPreview("preview");
        return entity;
    }

    private static SessionEntity pruneCandidate(
        UUID id,
        String title,
        String source,
        String endReason,
        int messageCount
    ) {
        SessionEntity entity = sessionEntity(title);
        entity.setId(id);
        entity.setSource(source);
        entity.setEndReason(endReason);
        entity.setSessionStatus("ended");
        entity.setModelName("gpt-test");
        entity.setCreatedAt(BASE_TIME.minusSeconds(3600));
        entity.setUpdatedAt(BASE_TIME.minusSeconds(60));
        entity.setLastActive(BASE_TIME.minusSeconds(120));
        entity.setMessageCount(messageCount);
        entity.setPinned(false);
        entity.setArchived(false);
        return entity;
    }
}
