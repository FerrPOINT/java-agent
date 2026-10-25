package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authenticated identity wins over client-supplied ownership before session access. */
class SessionQueryIdentityTest {
    private final SessionRepository repository = mock(SessionRepository.class);
    private final AgentSessionResolver resolver = mock(AgentSessionResolver.class);
    private final SessionQueryService service = new SessionQueryService(repository,
            mock(MessageRepository.class), resolver, Mappers.getMapper(DomainDtoMapper.class),
            new AgentProperties(), mock(ApplicationEventPublisher.class));

    @AfterEach
    void cleanup() { UserContext.clear(); }

    @Test
    void callerCannotListAnExplicitForeignOwner() {
        UserContext.set("owner", UserContext.ROLE_USER);
        when(repository.findAllByUserId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<SessionEntity>(List.of()));
        service.listSessions(50, 0, "foreign");
        verify(repository).findAllByUserId(eq("owner"), any(Pageable.class));
    }

    @Test
    void callerCannotCreateForAnotherOwner() {
        UserContext.set("owner", UserContext.ROLE_USER);
        Session session = mock(Session.class);
        when(session.id()).thenReturn(UUID.randomUUID());
        when(resolver.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        assertThat(service.createSession("foreign", "noop", null)).containsEntry("user_id", "owner");
        verify(resolver).createSession("owner", "openai-compatible", "noop");
    }

    @Test
    void chatExistenceCheckRejectsForeignSessionBeforeAsyncDispatch() {
        UserContext.set("owner", UserContext.ROLE_USER);
        UUID id = UUID.randomUUID();
        SessionEntity foreign = new SessionEntity();
        foreign.setUserId("foreign");
        when(repository.findById(id)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.sessionExists(id))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void chatExistenceCheckAllowsOwnAndRejectsMissingSession() {
        UserContext.set("owner", UserContext.ROLE_USER);
        UUID id = UUID.randomUUID();
        SessionEntity owned = new SessionEntity();
        owned.setUserId("owner");
        when(repository.findById(id)).thenReturn(Optional.of(owned));
        assertThat(service.sessionExists(id)).isTrue();
        assertThat(service.sessionExists(UUID.randomUUID())).isFalse();
    }
}
