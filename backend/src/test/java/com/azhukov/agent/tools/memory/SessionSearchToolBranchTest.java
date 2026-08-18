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
 * Branch coverage tests for {@link SessionSearchTool} — 4-mode version.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionSearchToolBranchTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private SessionLineageService sessionLineageService;

    private SessionSearchTool tool;

    @BeforeEach
    void setUp() {
        SessionSearchService service = new SessionSearchService(sessionRepository, messageRepository, sessionLineageService);
        tool = new SessionSearchTool(service, new ObjectMapper());

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
        e.setId(id); e.setTitle(title); e.setUserId("user-1");
        e.setCreatedAt(createdAt); e.setUpdatedAt(createdAt);
        e.setSource("telegram"); e.setMessageCount(0);
        return e;
    }

    private MessageEntity msgEntity(UUID id, UUID sId, String content, String role, Instant t) {
        MessageEntity m = new MessageEntity();
        m.setId(id); m.setSessionId(sId); m.setContent(content); m.setRole(role);
        m.setCreatedAt(t); m.setActive(true); m.setCompacted(false);
        return m;
    }

    @Test
    void readLargeSession_truncated() {
        UUID sId = UUID.randomUUID();
        Instant t = Instant.parse("2025-01-10T10:00:00Z");
        List<MessageEntity> msgs = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            msgs.add(msgEntity(UUID.randomUUID(), sId, "msg " + i, i % 2 == 0 ? "user" : "assistant", t.plusSeconds(i)));
        }
        when(sessionRepository.findById(sId)).thenReturn(Optional.of(sessionEntity(sId, "Big Session", t)));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId)).thenReturn(msgs);

        ToolResult result = tool.execute("{\"session_id\":\"" + sId + "\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"truncated\":true");
        assertThat(result.content()).contains("\"message_count\":35");
    }

    @Test
    void browseEmptySessions_returnsEmpty() {
        ToolResult result = tool.execute("{}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"count\":0");
    }

    @Test
    void discoveryLimitClampedTo10() {
        ToolResult result = tool.execute("{\"query\":\"test\",\"limit\":100}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"mode\":\"discover\"");
    }

    @Test
    void scrollAnchorNotFound_returnsFail() {
        UUID sId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();

        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "Test", Instant.now())));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of(msgEntity(UUID.randomUUID(), sId, "other msg", "user", Instant.now())));

        ToolResult result = tool.execute(
            String.format("{\"session_id\":\"%s\",\"around_message_id\":\"%s\"}", sId, anchorId),
            assistant(), session()
        );

        assertThat(result.success()).isFalse();
    }

    @Test
    void readSessionWithNoMessages_returnsEmpty() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "Empty", Instant.now())));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of());

        ToolResult result = tool.execute("{\"session_id\":\"" + sId + "\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("\"message_count\":0");
    }

    @Test
    void browseFiltersHiddenSources() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.listRecentExcludingSources(any(), any()))
            .thenReturn(List.of(sessionEntity(sId, "Visible Session", Instant.now())));

        ToolResult result = tool.execute("{}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Visible Session");
    }

    @Test
    void discoveryWithProfile_resolvesSessionLink() {
        UUID sId = UUID.randomUUID();
        Instant t = Instant.parse("2025-01-10T10:00:00Z");
        UUID mId = UUID.randomUUID();

        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(msgEntity(mId, sId, "test content", "user", t)));
        when(sessionRepository.findById(sId))
            .thenReturn(Optional.of(sessionEntity(sId, "Profile Test", t)));
        when(messageRepository.findById(mId))
            .thenReturn(Optional.of(msgEntity(mId, sId, "test content", "user", t)));
        when(sessionLineageService.findAncestorSessionIds(sId))
            .thenReturn(List.of(sId));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sId))
            .thenReturn(List.of(msgEntity(mId, sId, "test content", "user", t)));

        ToolResult result = tool.execute("{\"query\":\"test\",\"profile\":\"work\"}", assistant(), session());

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("@session:work/");
    }
}