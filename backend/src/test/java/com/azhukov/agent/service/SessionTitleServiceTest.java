package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTitleServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("12345678-1234-1234-1234-123456789012");

    private ModelClient modelClient;
    private SessionRepository sessionRepository;
    private AgentProperties properties;
    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        properties = new AgentProperties();
        properties.getCore().setAutoTitleSession(true);
        service = new SessionTitleService(modelClient, sessionRepository, properties);
    }

    @Test
    void updatesTitleToTrimmedUserTextForNewSession() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setTitle("New chat");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        service.maybeUpdateTitle(
            SESSION_ID,
            List.of(Message.user("   Help me plan a trip   ")),
            true
        );

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Help me plan a trip");
    }

    @Test
    void truncatesLongMessageToEightyCharsWithEllipsis() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setTitle("New chat");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        String longMessage = "a".repeat(90);
        service.maybeUpdateTitle(SESSION_ID, List.of(Message.user(longMessage)), true);

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("a".repeat(80) + "...");
    }

    @Test
    void doesNothingWhenAutoTitleIsDisabled() {
        properties.getCore().setAutoTitleSession(false);

        service.maybeUpdateTitle(
            SESSION_ID,
            List.of(Message.user("Short question")),
            true
        );

        verify(sessionRepository, never()).findById(any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void doesNothingForExistingSession() {
        service.maybeUpdateTitle(
            SESSION_ID,
            List.of(Message.user("Short question")),
            false
        );

        verify(sessionRepository, never()).findById(any());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void usesLlmGeneratedTitleWhenModelReturnsContent() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setTitle("New chat");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        String userMessage = "I need help writing a Java unit test for session title generation with Mockito".repeat(2);
        when(modelClient.complete(any(), any()))
            .thenReturn(ChatResponse.text("Mockito Title Test"));

        service.maybeUpdateTitle(SESSION_ID, List.of(Message.user(userMessage)), true);

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Mockito Title Test");
    }

    @Test
    void fallsBackToTruncationWhenLlmThrows() {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setTitle("New chat");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        String longMessage = "x".repeat(120);
        when(modelClient.complete(any(), any()))
            .thenThrow(new RuntimeException("LLM service unavailable"));

        service.maybeUpdateTitle(SESSION_ID, List.of(Message.user(longMessage)), true);

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("x".repeat(80) + "...");
    }
}
