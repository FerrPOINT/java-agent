package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.core.agent.SessionLineageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionSearchTool} — 4-mode session search.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionSearchToolTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private SessionLineageService sessionLineageService;

    private SessionSearchTool tool;

    @BeforeEach
    void setUp() {
        SessionSearchService service = new SessionSearchService(sessionRepository, messageRepository, sessionLineageService);
        tool = new SessionSearchTool(service, new ObjectMapper());

        // Default lenient stubs
        lenient().when(sessionRepository.findByTitleIgnoreCase(anyString())).thenReturn(null);
        lenient().when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        lenient().when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());
        lenient().when(messageRepository.searchByContentFtsExcludingSources(anyString(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        lenient().when(sessionRepository.searchByTitleFtsExcludingSources(anyString(), any()))
            .thenThrow(new RuntimeException("FTS not available"));
        lenient().when(sessionRepository.listRecentExcludingSources(any(), any())).thenReturn(List.of());
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user-1", "test", "openai-compatible", "gpt-4", null, Map.of(), null);
    }

    private Message assistant() {
        return Message.assistant("test", 0);
    }

    private SessionEntity sessionEntity(UUID id, String title, Instant createdAt) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setTitle(title);
        e.setUserId("user-1");
        e.setCreatedAt(createdAt);
        e.setUpdatedAt(createdAt);
        e.setSource("telegram");
        e.setMessageCount(0);
        return e;
    }

    private MessageEntity msgEntity(UUID id, UUID sId, String content, String role, Instant t) {
        MessageEntity m = new MessageEntity();
        m.setId(id); m.setSessionId(sId); m.setContent(content); m.setRole(role);
        m.setCreatedAt(t); m.setActive(true); m.setCompacted(false);
        return m;
    }

    // ── BROWSE mode ──

    @Test
    void browseNoArgs_returnsRecentSessions() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        Instant t1 = Instant.parse("2025-01-10T10:00:00Z");
        Instant t2 = Instant.parse("2025-01-15T10:00:00Z");

        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(
                sessionEntity(s2, "Recent Chat", t2),
                sessionEntity(s1, "Older Chat", t1)
            ));

        ToolResult result = tool.execute("{}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"browse\"");
        assertThat(result.content()).contains("Recent Chat");
        assertThat(result.content()).contains("Older Chat");
    }

    @Test
    void browseExcludesCurrentSession() {
        UUID currentId = UUID.randomUUID();
        Session currentSession = new Session(currentId, "user-1", "test", "openai-compatible", "gpt-4", null, Map.of(), null);
        UUID otherId = UUID.randomUUID();

        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(sessionEntity(otherId, "Other", Instant.now())));
        when(sessionLineageService.findAncestorSessionIds(currentId))
            .thenReturn(List.of(currentId));

        ToolResult result = tool.execute("{}", assistant(), currentSession);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Other");
        assertThat(result.content()).doesNotContain(currentId.toString());
    }

    // ── READ mode ──

    @Test
    void readSession_returnsMessages() {
        UUID sId = UUID.randomUUID();
        Instant t = Instant.parse("2025-01-10T10:00:00Z");

        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "Test Session", t)));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of(
                msgEntity(UUID.randomUUID(), sId, "Hello", "user", t),
                msgEntity(UUID.randomUUID(), sId, "Hi there", "assistant", t.plusSeconds(1))
            ));

        ToolResult result = tool.execute("{\"session_id\":\"" + sId + "\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"read\"");
        assertThat(result.content()).contains("Test Session");
        assertThat(result.content()).contains("Hello");
        assertThat(result.content()).contains("Hi there");
    }

    @Test
    void readSessionNotFound_returnsFail() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findById(sId)).thenReturn(Optional.empty());

        ToolResult result = tool.execute("{\"session_id\":\"" + sId + "\"}", assistant(), session());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("session_id not found");
    }

    // ── SCROLL mode ──

    @Test
    void scrollReturnsWindow() {
        UUID sId = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        UUID m3 = UUID.randomUUID();
        Instant t = Instant.parse("2025-01-10T10:00:00Z");

        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "Scroll Test", t)));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of(
                msgEntity(m1, sId, "msg1", "user", t),
                msgEntity(m2, sId, "msg2 anchor", "assistant", t.plusSeconds(1)),
                msgEntity(m3, sId, "msg3", "user", t.plusSeconds(2))
            ));

        ToolResult result = tool.execute(
            String.format("{\"session_id\":\"%s\",\"around_message_id\":\"%s\",\"window\":1}", sId, m2),
            assistant(), session()
        );

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"scroll\"");
        assertThat(result.content()).contains("msg2 anchor");
    }

    @Test
    void scrollInvalidSessionId_returnsFail() {
        ToolResult result = tool.execute(
            "{\"session_id\":\"not-a-uuid\",\"around_message_id\":\"123\"}",
            assistant(), session()
        );
        assertThat(result.success()).isFalse();
    }

    // ── DISCOVERY mode ──

    @Test
    void discoveryWithQuery_returnsResults() {
        UUID sId = UUID.randomUUID();
        UUID mId = UUID.randomUUID();
        Instant t = Instant.parse("2025-01-10T10:00:00Z");

        when(messageRepository.findByContentContainingIgnoreCase("kubernetes"))
            .thenReturn(List.of(msgEntity(mId, sId, "Let's set up kubernetes", "user", t)));
        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "K8s Discussion", t)));
        when(messageRepository.findById(mId))
            .thenReturn(Optional.of(msgEntity(mId, sId, "Let's set up kubernetes", "user", t)));
        when(sessionLineageService.findAncestorSessionIds(sId))
            .thenReturn(List.of(sId));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of(msgEntity(mId, sId, "Let's set up kubernetes", "user", t)));

        ToolResult result = tool.execute("{\"query\":\"kubernetes\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"discover\"");
        assertThat(result.content()).contains("K8s Discussion");
    }

    @Test
    void discoveryNoResults_returnsSuccess() {
        ToolResult result = tool.execute("{\"query\":\"nonexistent\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"discover\"");
        assertThat(result.content()).contains("\"count\":0");
    }

    @Test
    void discoveryInvalidSessionIdFormat_returnsFail() {
        ToolResult result = tool.execute(
            "{\"session_id\":\"not-a-uuid\"}",
            assistant(), session()
        );
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid session_id format");
    }
}