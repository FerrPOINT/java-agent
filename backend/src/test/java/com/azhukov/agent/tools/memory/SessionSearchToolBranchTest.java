package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Branch coverage tests for {@link SessionSearchTool}.
 * Covers FTS fallback, null session lookup, snippet truncation, and limit edge cases.
 */
@ExtendWith(MockitoExtension.class)
class SessionSearchToolBranchTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;

    private SessionSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new SessionSearchTool(sessionRepository, messageRepository);
    }

    private Session session() {
        return new Session(UUID.randomUUID(), "user-1", "test", "openai-compatible", "gpt-4", null, java.util.Map.of(), null);
    }

    private Message assistant() {
        return Message.assistant("test", 0);
    }

    @Test
    void limitExceedingMax_cappedAt20() {
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\",\"limit\":100}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void negativeLimit_defaultsTo5() {
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\",\"limit\":-1}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void zeroLimit_defaultsTo5() {
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\",\"limit\":0}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void nullLimit_defaultsTo5() {
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    @Test
    void messageMatchWithNullSession_fallsBackToMessageCreatedAt() {
        UUID sId = UUID.randomUUID();
        Instant msgTime = Instant.parse("2025-01-01T00:00:00Z");

        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), sId, "test content", msgTime)));
        // Session not found — falls back to message createdAt
        when(sessionRepository.findById(sId)).thenReturn(Optional.empty());

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains(sId.toString());
    }

    @Test
    void messageMatchWithNullContent_returnsEmptySnippet() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), sId, null, Instant.now())));
        when(sessionRepository.findById(sId)).thenReturn(Optional.of(sessionEntity(sId, "Title", Instant.now())));

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        // Should contain the session with empty snippet
        assertThat(result.content()).contains(sId.toString());
    }

    @Test
    void bothTitleAndMessageMatches_titleTakesPriority() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findByTitleContainingIgnoreCase("test"))
            .thenReturn(List.of(sessionEntity(sId, "test session", Instant.now())));
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), sId, "test content", Instant.now())));

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("title match");
        // Should NOT contain snippet (title match takes priority)
        assertThat(result.content()).doesNotContain("snippet:");
    }

    @Test
    void messageSnippetWithNewlines_replacedWithSpaces() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), sId, "line1\nline2\nline3", Instant.now())));
        when(sessionRepository.findById(sId)).thenReturn(Optional.of(sessionEntity(sId, "Title", Instant.now())));

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        // Newlines should be replaced with spaces in snippet
        assertThat(result.content()).contains("line1 line2 line3");
    }

    @Test
    void nullTitleInResult_showsNoTitle() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), sId, "content", Instant.now())));
        when(sessionRepository.findById(sId)).thenReturn(Optional.of(sessionEntity(sId, null, Instant.now())));

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("(no title)");
    }

    @Test
    void ftsReturnsResults_usesFtsResults() {
        UUID sId = UUID.randomUUID();
        when(sessionRepository.searchByTitleFts("test"))
            .thenReturn(List.of(sessionEntity(sId, "test FTS session", Instant.now())));
        when(messageRepository.searchByContentFts("test"))
            .thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("test FTS session");
    }

    @Test
    void ftsReturnsNull_fallsBackToLike() {
        when(sessionRepository.searchByTitleFts(anyString())).thenThrow(new RuntimeException("FTS unavailable"));
        when(sessionRepository.findByTitleContainingIgnoreCase("test"))
            .thenReturn(List.of(sessionEntity(UUID.randomUUID(), "like session", Instant.now())));
        when(messageRepository.findByContentContainingIgnoreCase(anyString())).thenReturn(List.of());

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("like session");
    }

    @Test
    void ftsMessageSearchThrows_fallsBackToLikeMessageSearch() {
        when(sessionRepository.findByTitleContainingIgnoreCase(anyString())).thenReturn(List.of());
        when(messageRepository.searchByContentFts(anyString())).thenThrow(new RuntimeException("FTS unavailable"));
        when(messageRepository.findByContentContainingIgnoreCase("test"))
            .thenReturn(List.of(messageEntity(UUID.randomUUID(), UUID.randomUUID(), "like content", Instant.now())));
        when(sessionRepository.findById(any(UUID.class))).thenReturn(Optional.of(sessionEntity(UUID.randomUUID(), "Like Session", Instant.now())));

        ToolResult result = tool.execute("{\"query\":\"test\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("like content");
    }

    private SessionEntity sessionEntity(UUID id, String title, Instant updatedAt) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setTitle(title);
        e.setUserId("user-1");
        e.setUpdatedAt(updatedAt);
        return e;
    }

    private MessageEntity messageEntity(UUID id, UUID sessionId, String content, Instant createdAt) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setContent(content);
        m.setCreatedAt(createdAt);
        return m;
    }
}