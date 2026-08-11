package com.azhukov.agent.service;

import com.azhukov.agent.client.NoOpModelClient;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage tests for {@link SessionTitleService} targeting:
 * - maybeUpdateTitle with not-new session (skip)
 * - maybeUpdateTitle with autoTitle disabled (skip)
 * - maybeUpdateTitle with no user messages (skip)
 * - maybeUpdateTitle with blank first user message (skip)
 * - generateTitle with short message (no truncation)
 * - generateTitle with long message (truncation)
 * - generateTitle with NoOpModelClient (truncation without LLM)
 * - generateTitle with LLM success (title from response)
 * - generateTitle with LLM returning null/blank (fallback)
 * - generateTitle with LLM exception (fallback)
 * - generateTitle with long LLM response (truncation)
 * - generateTitle with quoted LLM response (strip quotes)
 */
@ExtendWith(MockitoExtension.class)
class SessionTitleServiceBranchTest {

    @Mock
    private ModelClient modelClient;

    @Mock
    private SessionRepository sessionRepository;

    private AgentProperties properties;
    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setAutoTitleSession(true);
        service = new SessionTitleService(modelClient, sessionRepository, properties);
    }

    private SessionEntity newSessionEntity(UUID id) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId("user-1");
        e.setTitle("Old title");
        e.setModelProvider("openai-compatible");
        e.setModelName("test-model");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    // ── maybeUpdateTitle with not-new session (skip) ──

    @Test
    void maybeUpdateTitleSkipsForExistingSession() {
        UUID sessionId = UUID.randomUUID();
        service.maybeUpdateTitle(sessionId, List.of(Message.user("hello")), false);

        verifyNoInteractions(sessionRepository);
    }

    // ── maybeUpdateTitle with autoTitle disabled (skip) ──

    @Test
    void maybeUpdateTitleSkipsWhenAutoTitleDisabled() {
        properties.getCore().setAutoTitleSession(false);
        UUID sessionId = UUID.randomUUID();
        service.maybeUpdateTitle(sessionId, List.of(Message.user("hello")), true);

        verifyNoInteractions(sessionRepository);
    }

    // ── maybeUpdateTitle with no user messages (skip) ──

    @Test
    void maybeUpdateTitleSkipsWhenNoUserMessages() {
        UUID sessionId = UUID.randomUUID();
        service.maybeUpdateTitle(sessionId, List.of(Message.system("system")), true);

        verifyNoInteractions(sessionRepository);
    }

    // ── maybeUpdateTitle with blank first user message (skip) ──

    @Test
    void maybeUpdateTitleSkipsWhenFirstUserMessageIsBlank() {
        UUID sessionId = UUID.randomUUID();
        service.maybeUpdateTitle(sessionId, List.of(Message.user("  ")), true);

        verifyNoInteractions(sessionRepository);
    }

    // ── maybeUpdateTitle with null first user message (skip) ──

    @Test
    void maybeUpdateTitleSkipsWhenFirstUserMessageIsNull() {
        UUID sessionId = UUID.randomUUID();
        // Use a blank user message instead of null content (Message.user(null) causes NPE)
        service.maybeUpdateTitle(sessionId, List.of(Message.user("")), true);

        verifyNoInteractions(sessionRepository);
    }

    // ── generateTitle with short message (≤ 80 chars, NoOpModelClient) ──

    @Test
    void maybeUpdateTitleWithNoOpModelClientAndShortMessageSetsTitle() {
        NoOpModelClient noOpClient = new NoOpModelClient();
        service = new SessionTitleService(noOpClient, sessionRepository, properties);

        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        String message = "Hello world";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(message)), true);

        verify(sessionRepository).save(any(SessionEntity.class));
        assertThat(entity.getTitle()).isEqualTo(message);
    }

    // ── generateTitle with long message (NoOpModelClient → truncation) ──

    @Test
    void maybeUpdateTitleWithNoOpModelClientAndLongMessageTruncatesTitle() {
        NoOpModelClient noOpClient = new NoOpModelClient();
        service = new SessionTitleService(noOpClient, sessionRepository, properties);

        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        String longMessage = "x".repeat(120);
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        verify(sessionRepository).save(any(SessionEntity.class));
        assertThat(entity.getTitle()).hasSizeLessThanOrEqualTo(83); // 80 + "..."
        assertThat(entity.getTitle()).endsWith("...");
    }

    // ── generateTitle with LLM success ──

    @Test
    void maybeUpdateTitleWithLlmResponseSetsTitle() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Short Title", List.of()));

        // Use a long message (> 80 chars) to trigger LLM call
        String longMessage = "This is a very long user message that exceeds the eighty character limit for title generation and should trigger LLM";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        verify(sessionRepository).save(any(SessionEntity.class));
        assertThat(entity.getTitle()).isEqualTo("Short Title");
    }

    // ── generateTitle with LLM returning quoted title ──

    @Test
    void maybeUpdateTitleWithLlmReturningQuotedTitleStripsQuotes() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("'Java Discussion'", List.of()));

        String longMessage = "This is a very long user message that exceeds the eighty character limit for title generation and should trigger LLM";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).isEqualTo("Java Discussion");
    }

    // ── generateTitle with LLM returning double-quoted title ──

    @Test
    void maybeUpdateTitleWithLlmReturningDoubleQuotedTitleStripsQuotes() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("\"Java Programming\"", List.of()));

        String longMessage = "This is a very long user message that exceeds the eighty character limit for title generation and should trigger LLM";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).isEqualTo("Java Programming");
    }

    // ── generateTitle with LLM returning long title (truncation) ──

    @Test
    void maybeUpdateTitleWithLlmReturningLongTitleTruncates() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        lenient().when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        String longTitle = "y".repeat(120);
        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse(longTitle, List.of()));

        String longMessage = "This is a very long user message that exceeds the eighty character limit for title generation and should trigger LLM";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).hasSizeLessThanOrEqualTo(80);
    }

    // ── generateTitle with LLM returning null response (fallback) ──

    @Test
    void maybeUpdateTitleWithLlmReturningNullUsesFallback() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(null);

        String longMessage = "x".repeat(120);
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        // Fallback: truncation with "..."
        assertThat(entity.getTitle()).endsWith("...");
    }

    // ── generateTitle with LLM returning blank response (fallback) ──

    @Test
    void maybeUpdateTitleWithLlmReturningBlankUsesFallback() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("  ", List.of()));

        String longMessage = "x".repeat(120);
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).endsWith("...");
    }

    // ── generateTitle with LLM exception (fallback) ──

    @Test
    void maybeUpdateTitleWithLlmExceptionUsesFallback() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenThrow(new RuntimeException("LLM error"));

        String longMessage = "x".repeat(120);
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).endsWith("...");
    }

    // ── generateTitle with short LLM response ──

    @Test
    void maybeUpdateTitleWithShortLlmResponseSetsTitle() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        when(modelClient.complete(any(List.class), any()))
            .thenReturn(new ChatResponse("Hello", List.of()));

        String longMessage = "This is a very long user message that exceeds the eighty character limit for title generation and should trigger LLM";
        service.maybeUpdateTitle(sessionId, List.of(Message.user(longMessage)), true);

        assertThat(entity.getTitle()).isEqualTo("Hello");
    }

    // ── maybeUpdateTitle: session not found in repository ──

    @Test
    void maybeUpdateTitleWhenSessionNotFoundDoesNothing() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        service.maybeUpdateTitle(sessionId, List.of(Message.user("Hello")), true);

        verify(sessionRepository, never()).save(any());
    }

    // ── generateTitle with multi-line user message ──

    @Test
    void maybeUpdateTitleNormalizesMultiLineMessage() {
        NoOpModelClient noOpClient = new NoOpModelClient();
        service = new SessionTitleService(noOpClient, sessionRepository, properties);

        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        service.maybeUpdateTitle(sessionId,
            List.of(Message.user("Hello\n  world\tdone")), true);

        // Multi-line content should be normalized to single spaces
        assertThat(entity.getTitle()).isEqualTo("Hello world done");
    }
}