package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SessionResolver} — covers the "existing session" branch
 * and the "create new session" branch.
 */
@ExtendWith(MockitoExtension.class)
class SessionResolverTest {

    @Mock private SessionRepository sessionRepository;

    private AgentProperties properties;
    private SessionResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("gpt-4");
        resolver = new SessionResolver(sessionRepository, properties);
    }

    @Test
    void resolveReturnsExistingSession() {
        UUID id = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setUserId("user-1");
        entity.setTitle("Telegram alice");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("gpt-4");

        when(sessionRepository.findByUserId("user-1")).thenReturn(entity);

        SessionSource source = new SessionSource(Platform.TELEGRAM, "chat-1", "user-1", "alice", "Alice");
        Session session = resolver.resolve(source);

        assertThat(session.id()).isEqualTo(id);
        assertThat(session.userId()).isEqualTo("user-1");
        assertThat(session.title()).isEqualTo("Telegram alice");
        verify(sessionRepository).findByUserId("user-1");
        verify(sessionRepository).touchUpdatedAt(eq(id), any(Instant.class));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void resolveCreatesNewSessionWhenNotFound() {
        when(sessionRepository.findByUserId("chat-1")).thenReturn(null);

        // Simulate save assigning an id
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        SessionSource source = new SessionSource(Platform.TELEGRAM, "chat-1", null, "bob", "Bob");
        Session session = resolver.resolve(source);

        assertThat(session.userId()).isEqualTo("chat-1");
        assertThat(session.title()).isEqualTo("Telegram bob");
        assertThat(session.modelProvider()).isEqualTo("openai-compatible");
        assertThat(session.modelName()).isEqualTo("gpt-4");
        assertThat(session.id()).isNotNull();

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        SessionEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("chat-1");
        assertThat(saved.getTitle()).isEqualTo("Telegram bob");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void resolveUsesChatIdWhenUserIdIsNull() {
        when(sessionRepository.findByUserId("chat-42")).thenReturn(null);

        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        SessionSource source = new SessionSource(Platform.TELEGRAM, "chat-42", null, "carol", "Carol");
        Session session = resolver.resolve(source);

        assertThat(session.userId()).isEqualTo("chat-42");
    }
}