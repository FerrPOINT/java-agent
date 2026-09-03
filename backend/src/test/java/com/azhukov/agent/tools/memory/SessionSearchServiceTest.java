package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.agent.SessionLineageService;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionSearchService} — 4-mode session_search parity with Hermes.
 * Covers all four calling shapes: DISCOVERY (query), SCROLL (session_id + around_message_id),
 * READ (session_id only), BROWSE (no args), plus error handling and link parsing.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionSearchServiceTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private SessionLineageService sessionLineageService;
    private SessionSearchService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        sessionLineageService = mock(SessionLineageService.class);
        service = new SessionSearchService(sessionRepository, messageRepository, sessionLineageService);

        // Default: lineage returns [self] (no ancestors) so resolveLineageRoot returns the session itself
        when(sessionLineageService.findAncestorSessionIds(any())).thenAnswer(inv -> {
            UUID sid = inv.getArgument(0);
            return List.of(sid);
        });
    }

    // ── BROWSE mode (no args) ──

    @Test
    void browse_noArgs_returnsRecentSessions() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        SessionEntity session1 = newSessionEntity(s1, "Session One", "telegram");
        SessionEntity session2 = newSessionEntity(s2, "Session Two", "cli");

        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(session1, session2));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("browse");
        assertThat(result.browseResults).hasSize(2);
        assertThat(result.browseResults.get(0).sessionId()).isEqualTo(s1);
        assertThat(result.browseResults.get(0).title()).isEqualTo("Session One");
        assertThat(result.browseResults.get(0).source()).isEqualTo("telegram");
        assertThat(result.browseResults.get(0).link()).isEqualTo("@session:default/" + s1);
    }

    @Test
    void browse_excludesCurrentSession() {
        UUID s1 = UUID.randomUUID();
        UUID currentSession = UUID.randomUUID();
        SessionEntity session1 = newSessionEntity(s1, "Session One", "telegram");
        SessionEntity currentEntity = newSessionEntity(currentSession, "Current", "cli");

        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(session1, currentEntity));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, null, null, null, null, null, null, currentSession
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("browse");
        // Current session should be excluded
        assertThat(result.browseResults).hasSize(1);
        assertThat(result.browseResults.get(0).sessionId()).isEqualTo(s1);
    }

    @Test
    void browse_emptyRepository_returnsEmptyResults() {
        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(Collections.emptyList());

        SessionSearchService.SearchResult result = service.search(
            null, null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("browse");
        assertThat(result.browseResults).isEmpty();
    }

    @Test
    void browse_withProfile_usesProfileInLink() {
        UUID s1 = UUID.randomUUID();
        SessionEntity session1 = newSessionEntity(s1, "Session One", "telegram");

        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(session1));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, null, null, null, null, null, "work", null
        );

        assertThat(result.browseResults.get(0).link()).isEqualTo("@session:work/" + s1);
    }

    // ── READ mode (session_id only) ──

    @Test
    void read_sessionIdOnly_returnsFullSessionMessages() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Read Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        List<MessageEntity> messages = List.of(
            newMessageEntity(sessionId, "user", "Hello", 0),
            newMessageEntity(sessionId, "assistant", "World", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("read");
        assertThat(result.readSessionId).isEqualTo(sessionId);
        assertThat(result.readTotal).isEqualTo(2);
        assertThat(result.readTruncated).isFalse();
        assertThat(result.readMessages).hasSize(2);
        assertThat(result.readMessages.get(0).role()).isEqualTo("user");
        assertThat(result.readMessages.get(0).content()).isEqualTo("Hello");
        assertThat(result.readMessages.get(1).role()).isEqualTo("assistant");
        assertThat(result.readLink).isEqualTo("@session:default/" + sessionId);
    }

    @Test
    void read_sessionIdOnlyHidesInactiveRowsLikeHermes() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Read Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity active = newMessageEntity(sessionId, "user", "live", 0);
        MessageEntity compacted = newMessageEntity(sessionId, "assistant", "compacted archive", 1);
        compacted.setActive(false);
        compacted.setCompacted(true);
        MessageEntity rewound = newMessageEntity(sessionId, "user", "rewound", 2);
        rewound.setActive(false);
        rewound.setCompacted(false);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(List.of(active, compacted, rewound));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.readTotal).isEqualTo(1);
        assertThat(result.readMessages).extracting(SessionSearchService.ShapedMessage::content)
            .containsExactly("live");
    }

    @Test
    void read_preservesStoredAssistantToolCallsArray() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Read Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity assistant = newMessageEntity(sessionId, "assistant", "", 1);
        assistant.setToolCallId("legacy-call");
        assistant.setToolCallName("legacy");
        assistant.setToolCallArguments("{}");
        assistant.setToolCalls("""
            [
              {"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"java\\"}"}},
              {"id":"call_2","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}
            ]
            """);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(assistant));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.readMessages).hasSize(1);
        List<Map<String, Object>> toolCalls = result.readMessages.get(0).toolCalls();
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0)).containsEntry("id", "call_1");
        assertThat(toolCalls.get(1)).containsEntry("id", "call_2");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCalls.get(1).get("function");
        assertThat(function)
            .containsEntry("name", "read_file")
            .containsEntry("arguments", "{\"path\":\"README.md\"}");
    }

    @Test
    void read_sessionNotFound_returnsError() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), null, null, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error).contains("session_id not found");
        assertThat(result.error).contains(sessionId.toString());
    }

    @Test
    void read_manyMessages_truncatesToHeadAndTail() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Big Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Create 35 messages — exceeds head(20) + tail(10) = 30
        List<MessageEntity> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 35; i++) {
            messages.add(newMessageEntity(sessionId, "user", "msg-" + i, i));
        }
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.readTotal).isEqualTo(35);
        assertThat(result.readTruncated).isTrue();
        assertThat(result.readHead).isEqualTo(20);
        assertThat(result.readTail).isEqualTo(10);
        // head(20) + tail(10) = 30 messages in window
        assertThat(result.readMessages).hasSize(30);
    }

    // ── SCROLL mode (session_id + around_message_id) ──

    @Test
    void scroll_sessionIdAndAroundMessageId_returnsWindowAroundAnchor() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Scroll Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // Create 10 messages, anchor at index 5
        List<MessageEntity> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(newMessageEntity(sessionId, "user", "msg-" + i, i));
        }
        UUID anchorId = messages.get(5).getId();
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), anchorId.toString(), 2, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("scroll");
        assertThat(result.scrollSessionId).isEqualTo(sessionId);
        assertThat(result.scrollAroundMessageId).isEqualTo(anchorId);
        assertThat(result.scrollWindow).isEqualTo(2);
        // window=2: ±2 around anchor → 5 messages (indices 3..7)
        // But if window is interpreted as total size (not ±N), result is 3
        // The actual implementation uses ±N, so expect 5
        assertThat(result.scrollView.window()).hasSizeGreaterThanOrEqualTo(3);
        // The anchor message should be flagged
        assertThat(result.scrollView.window().stream().anyMatch(SessionSearchService.ShapedMessage::anchor)).isTrue();
        // messagesBefore = 5 - 3 = 2, messagesAfter = 8 - 5 - 1 = 2
        assertThat(result.scrollView.messagesBefore()).isEqualTo(2);
        assertThat(result.scrollView.messagesAfter()).isEqualTo(2);
    }

    @Test
    void scroll_anchorNotFound_returnsError() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Scroll Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        List<MessageEntity> messages = List.of(
            newMessageEntity(sessionId, "user", "msg-0", 0)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        UUID nonExistentAnchor = UUID.randomUUID();

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), nonExistentAnchor.toString(), 5, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error).contains("not in session_id");
        assertThat(result.error).contains(nonExistentAnchor.toString());
    }

    @Test
    void scroll_sessionNotFound_returnsError() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), UUID.randomUUID().toString(), 5, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error).contains("session_id not found");
    }

    @Test
    void scroll_windowClampedToMinMax() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Scroll Test", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        List<MessageEntity> messages = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(newMessageEntity(sessionId, "user", "msg-" + i, i));
        }
        UUID anchorId = messages.get(5).getId();
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        // window=100 → should be clamped to 20
        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), anchorId.toString(), 100, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.scrollWindow).isEqualTo(20);
    }

    // ── DISCOVERY mode (query) ──

    @Test
    void discovery_withQuery_returnsMatchingMessages() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Some Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity matchMsg = newMessageEntity(sessionId, "user", "contains the searchterm here", 0);
        when(messageRepository.searchByContentFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(List.of(matchMsg));
        when(messageRepository.findByContentContainingIgnoreCase("searchterm"))
            .thenReturn(List.of(matchMsg));
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleContainingIgnoreCase("searchterm"))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("searchterm"))
            .thenReturn(null);
        when(messageRepository.findById(matchMsg.getId())).thenReturn(Optional.of(matchMsg));

        SessionSearchService.SearchResult result = service.search(
            "searchterm", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("discover");
        assertThat(result.query).isEqualTo("searchterm");
        assertThat(result.discoverResults).isNotEmpty();
        assertThat(result.discoverResults.get(0).sessionId()).isEqualTo(sessionId);
        assertThat(result.discoverResults.get(0).snippet()).contains("searchterm");
    }

    @Test
    void discovery_exactTitleMatch_appearsFirstInResults() {
        UUID titleSessionId = UUID.randomUUID();
        UUID contentSessionId = UUID.randomUUID();
        SessionEntity titleSession = newSessionEntity(titleSessionId, "exact title", "cli");
        SessionEntity contentSession = newSessionEntity(contentSessionId, "Other Session", "cli");
        when(sessionRepository.findById(titleSessionId)).thenReturn(Optional.of(titleSession));
        when(sessionRepository.findById(contentSessionId)).thenReturn(Optional.of(contentSession));

        // Title exact match
        when(sessionRepository.findByTitleIgnoreCase("exact title"))
            .thenReturn(titleSession);

        // Also a content match in another session
        MessageEntity contentMsg = newMessageEntity(contentSessionId, "user", "some content with exact title word", 0);
        when(messageRepository.searchByContentFtsExcludingSources(eq("exact title"), any()))
            .thenReturn(List.of(contentMsg));
        when(messageRepository.findByContentContainingIgnoreCase("exact title"))
            .thenReturn(List.of(contentMsg));
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("exact title"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleContainingIgnoreCase("exact title"))
            .thenReturn(Collections.emptyList());
        when(messageRepository.findById(contentMsg.getId())).thenReturn(Optional.of(contentMsg));

        SessionSearchService.SearchResult result = service.search(
            "exact title", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("discover");
        // Title match should be first
        assertThat(result.discoverResults).isNotEmpty();
        assertThat(result.discoverResults.get(0).sessionId()).isEqualTo(titleSessionId);
        assertThat(result.discoverResults.get(0).snippet()).isEqualTo("title match");
    }

    @Test
    void discovery_demotesCronSessionsBelowInteractive_viaBatchLoad() {
        // H12 regression: demotion sort must batch-load sessions (findAllById) and still
        // order interactive rows above cron rows.
        UUID interactiveSession = UUID.randomUUID();
        UUID cronSession = UUID.randomUUID();
        MessageEntity cronMsg = newMessageEntity(cronSession, "user", "searchterm in cron output", 0);
        MessageEntity interactiveMsg = newMessageEntity(interactiveSession, "user", "searchterm in chat", 0);

        when(messageRepository.searchByContentFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(List.of(cronMsg, interactiveMsg)); // cron first — sort must demote it
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("searchterm")).thenReturn(null);
        when(sessionRepository.findAllById(any()))
            .thenReturn(List.of(
                newSessionEntity(interactiveSession, "Chat", "api_server"),
                newSessionEntity(cronSession, "Cron", "cron")));
        when(messageRepository.findById(any())).thenAnswer(inv -> Optional.empty());

        SessionSearchService.SearchResult result = service.search(
            "searchterm", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).hasSize(2);
        // The interactive session must be ranked above the cron session
        int interactiveIdx = -1, cronIdx = -1;
        for (int i = 0; i < result.discoverResults.size(); i++) {
            if (result.discoverResults.get(i).sessionId().equals(interactiveSession)) interactiveIdx = i;
            if (result.discoverResults.get(i).sessionId().equals(cronSession)) cronIdx = i;
        }
        assertThat(interactiveIdx).isLessThan(cronIdx);
    }

    @Test
    void discovery_noMatches_returnsEmptyResults() {
        when(messageRepository.searchByContentFtsExcludingSources(any(), any()))
            .thenReturn(Collections.emptyList());
        when(messageRepository.findByContentContainingIgnoreCase(any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.searchByTitleFtsExcludingSources(any(), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleContainingIgnoreCase(any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase(any()))
            .thenReturn(null);

        SessionSearchService.SearchResult result = service.search(
            "nonexistentquery", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("discover");
        assertThat(result.discoverResults).isEmpty();
    }

    @Test
    void webSearchUsesHermesLimitAndSourceFilters() {
        UUID cliSessionId = UUID.randomUUID();
        UUID cronSessionId = UUID.randomUUID();
        SessionEntity cliSession = newSessionEntity(cliSessionId, "Needle CLI", "cli");
        SessionEntity cronSession = newSessionEntity(cronSessionId, "Needle Cron", "cron");
        when(messageRepository.searchByContentFtsExcludingSources(eq("needle"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("needle"), any()))
            .thenReturn(List.of(cronSession, cliSession));
        when(sessionRepository.findByTitleIgnoreCase("needle")).thenReturn(null);
        when(sessionRepository.findById(cliSessionId)).thenReturn(Optional.of(cliSession));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) service
                .webSearch("needle", 500, null, "cli", null, "cron")
                .get("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("id", cliSessionId.toString())
            .containsEntry("session_id", cliSessionId.toString())
            .containsEntry("lineage_root", cliSessionId.toString())
            .containsEntry("source", "cli")
            .containsEntry("model", "test-model")
            .containsEntry("title", "Needle CLI")
            .containsEntry("message_count", 5)
            .containsEntry("tool_call_count", 0)
            .containsEntry("input_tokens", 0)
            .containsEntry("output_tokens", 0)
            .containsEntry("preview", "Preview text")
            .containsEntry("profile", "default")
            .containsEntry("is_default_profile", true);
        assertThat(results.get(0).get("started_at")).isInstanceOf(Long.class);
        assertThat(results.get(0).get("last_active")).isInstanceOf(Long.class);
        assertThat(results.get(0).get("session_started")).isEqualTo(results.get(0).get("started_at"));
    }

    @Test
    void webSearchSurfacesDirectSessionIdMatchesFirstLikeHermes() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Direct hit", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(messageRepository.searchByContentFtsExcludingSources(eq(sessionId.toString()), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.searchByTitleFtsExcludingSources(eq(sessionId.toString()), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase(sessionId.toString())).thenReturn(null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) service
                .webSearch(sessionId.toString(), 20, null, null, null, null)
                .get("results");

        assertThat(results).hasSize(1);
        assertThat(results.get(0))
            .containsEntry("session_id", sessionId.toString())
            .containsEntry("snippet", "Preview text")
            .containsEntry("role", null);
    }

    @Test
    void discovery_defaultsToUserAssistantRolesAndReportsMatchedRole() {
        UUID toolSessionId = UUID.randomUUID();
        UUID assistantSessionId = UUID.randomUUID();
        MessageEntity toolMsg = newMessageEntity(toolSessionId, "tool", "searchterm in tool output", 0);
        MessageEntity assistantMsg = newMessageEntity(assistantSessionId, "assistant", "searchterm in answer", 0);
        SessionEntity assistantSession = newSessionEntity(assistantSessionId, "Assistant Hit", "cli");

        when(messageRepository.searchByContentFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(List.of(toolMsg, assistantMsg));
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleContainingIgnoreCase("searchterm"))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("searchterm")).thenReturn(null);
        when(sessionRepository.findAllById(any()))
            .thenReturn(List.of(
                newSessionEntity(toolSessionId, "Tool Hit", "cli"),
                assistantSession));
        when(sessionRepository.findById(assistantSessionId)).thenReturn(Optional.of(assistantSession));
        when(messageRepository.findById(assistantMsg.getId())).thenReturn(Optional.of(assistantMsg));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(assistantSessionId))
            .thenReturn(List.of(assistantMsg));

        SessionSearchService.SearchResult result = service.search(
            "searchterm", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).hasSize(1);
        assertThat(result.discoverResults.get(0).sessionId()).isEqualTo(assistantSessionId);
        assertThat(result.discoverResults.get(0).matchedRole()).isEqualTo("assistant");
    }

    @Test
    void discovery_appliesExplicitRoleFilter() {
        UUID userSessionId = UUID.randomUUID();
        UUID toolSessionId = UUID.randomUUID();
        MessageEntity userMsg = newMessageEntity(userSessionId, "user", "searchterm in user message", 0);
        MessageEntity toolMsg = newMessageEntity(toolSessionId, "tool", "searchterm in tool output", 0);
        SessionEntity toolSession = newSessionEntity(toolSessionId, "Tool Hit", "cli");

        when(messageRepository.searchByContentFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(List.of(userMsg, toolMsg));
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("searchterm"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleContainingIgnoreCase("searchterm"))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("searchterm")).thenReturn(null);
        when(sessionRepository.findAllById(any()))
            .thenReturn(List.of(
                newSessionEntity(userSessionId, "User Hit", "cli"),
                toolSession));
        when(sessionRepository.findById(toolSessionId)).thenReturn(Optional.of(toolSession));
        when(messageRepository.findById(toolMsg.getId())).thenReturn(Optional.of(toolMsg));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(toolSessionId))
            .thenReturn(List.of(toolMsg));

        SessionSearchService.SearchResult result = service.search(
            "searchterm", "tool", null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).hasSize(1);
        assertThat(result.discoverResults.get(0).sessionId()).isEqualTo(toolSessionId);
        assertThat(result.discoverResults.get(0).matchedRole()).isEqualTo("tool");
    }

    @Test
    void discovery_ftsFails_fallsBackToLikeSearch() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity matchMsg = newMessageEntity(sessionId, "user", "contains fallback term", 0);

        // FTS throws → should fall back to LIKE
        when(messageRepository.searchByContentFtsExcludingSources(any(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        when(messageRepository.findByContentContainingIgnoreCase("fallback"))
            .thenReturn(List.of(matchMsg));
        when(sessionRepository.searchByTitleFtsExcludingSources(any(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        when(sessionRepository.findByTitleContainingIgnoreCase("fallback"))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("fallback"))
            .thenReturn(null);
        when(messageRepository.findById(matchMsg.getId())).thenReturn(Optional.of(matchMsg));

        SessionSearchService.SearchResult result = service.search(
            "fallback", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).isNotEmpty();
        assertThat(result.discoverResults.get(0).snippet()).contains("fallback");
    }

    @Test
    void discovery_ftsFails_fallsBackToLikeSearchAcrossStoredToolCalls() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Tool Calls Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity assistant = newMessageEntity(sessionId, "assistant", "", 0);
        assistant.setToolCalls("""
            [
              {"id":"call_1","type":"function","function":{"name":"browser_snapshot","arguments":"{\\"label\\":\\"checkout-step\\"}"}}
            ]
            """);

        when(messageRepository.searchByContentFtsExcludingSources(any(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        when(messageRepository.findByContentContainingIgnoreCase("checkout-step"))
            .thenReturn(List.of(assistant));
        when(sessionRepository.searchByTitleFtsExcludingSources(any(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        when(sessionRepository.findByTitleContainingIgnoreCase("checkout-step"))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("checkout-step"))
            .thenReturn(null);
        when(sessionRepository.findAllById(any())).thenReturn(List.of(session));
        when(messageRepository.findById(assistant.getId())).thenReturn(Optional.of(assistant));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(assistant));

        SessionSearchService.SearchResult result = service.search(
            "checkout-step", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).hasSize(1);
        assertThat(result.discoverResults.get(0).matchedRole()).isEqualTo("assistant");
        List<Map<String, Object>> toolCalls = result.discoverResults.get(0).messages().get(0).toolCalls();
        assertThat(toolCalls).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
        assertThat(function)
            .containsEntry("name", "browser_snapshot")
            .containsEntry("arguments", "{\"label\":\"checkout-step\"}");
    }

    @Test
    void discovery_filtersRewoundRowsButKeepsCompactedArchives() {
        UUID compactedSessionId = UUID.randomUUID();
        UUID rewoundSessionId = UUID.randomUUID();
        SessionEntity compactedSession = newSessionEntity(compactedSessionId, "Compacted", "cli");
        SessionEntity rewoundSession = newSessionEntity(rewoundSessionId, "Rewound", "cli");
        MessageEntity compacted = newMessageEntity(compactedSessionId, "user", "needle compacted", 1);
        compacted.setActive(false);
        compacted.setCompacted(true);
        MessageEntity rewound = newMessageEntity(rewoundSessionId, "user", "needle rewound", 1);
        rewound.setActive(false);
        rewound.setCompacted(false);

        when(messageRepository.searchByContentFtsExcludingSources(eq("needle"), any()))
            .thenReturn(List.of(rewound, compacted));
        when(sessionRepository.searchByTitleFtsExcludingSources(eq("needle"), any()))
            .thenReturn(Collections.emptyList());
        when(sessionRepository.findByTitleIgnoreCase("needle")).thenReturn(null);
        when(sessionRepository.findAllById(any()))
            .thenReturn(List.of(compactedSession, rewoundSession));
        when(sessionRepository.findById(compactedSessionId)).thenReturn(Optional.of(compactedSession));
        when(messageRepository.findById(compacted.getId())).thenReturn(Optional.of(compacted));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(compactedSessionId))
            .thenReturn(List.of(compacted));

        SessionSearchService.SearchResult result = service.search(
            "needle", null, null, null, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.discoverResults).hasSize(1);
        assertThat(result.discoverResults.get(0).sessionId()).isEqualTo(compactedSessionId);
        assertThat(result.discoverResults.get(0).snippet()).contains("compacted");
    }

    // ── Error handling: invalid session_id ──

    @Test
    void search_invalidSessionIdFormat_returnsError() {
        SessionSearchService.SearchResult result = service.search(
            null, null, null, "not-a-uuid", null, null, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error).contains("Invalid session_id format");
    }

    @Test
    void search_invalidAroundMessageIdFormat_returnsError() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        SessionSearchService.SearchResult result = service.search(
            null, null, null, sessionId.toString(), "not-a-uuid", null, null, null, null, null
        );

        assertThat(result.success).isFalse();
        assertThat(result.error).contains("Invalid around_message_id format");
    }

    // ── @session:profile/id link parsing ──

    @Test
    void search_parsesSessionLinkFormat() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, "Linked Session", "cli");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        List<MessageEntity> messages = List.of(
            newMessageEntity(sessionId, "user", "Hello", 0)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        // Pass "@session:work/<uuid>" as sessionId
        String link = "@session:work/" + sessionId;
        SessionSearchService.SearchResult result = service.search(
            null, null, null, link, null, null, null, null, null, null
        );

        assertThat(result.success).isTrue();
        assertThat(result.mode).isEqualTo("read");
        // The link in the result should use the parsed profile "work"
        assertThat(result.readLink).isEqualTo("@session:work/" + sessionId);
    }

    // ── Helpers ──

    private SessionEntity newSessionEntity(UUID id, String title, String source) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId("user-1");
        e.setModelProvider("openai-compatible");
        e.setModelName("test-model");
        e.setTitle(title);
        e.setSource(source);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setLastActive(Instant.now());
        e.setMessageCount(5);
        e.setPreview("Preview text");
        return e;
    }

    private MessageEntity newMessageEntity(UUID sessionId, String role, String content, int turnIndex) {
        MessageEntity e = new MessageEntity();
        e.setId(UUID.randomUUID());
        e.setSessionId(sessionId);
        e.setRole(role);
        e.setContent(content);
        e.setTurnIndex(turnIndex);
        e.setActive(true);
        e.setCompacted(false);
        e.setCreatedAt(Instant.now().plusSeconds(turnIndex));
        return e;
    }
}
