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
            new AgentProperties(), mock(ApplicationEventPublisher.class), mock(ProfileModelRouting.class));

    @AfterEach
    void cleanup() { UserContext.clear(); }

    @Test
    void callerCannotListAnExplicitForeignOwner() {
        UserContext.set("owner", UserContext.ROLE_USER);
        when(repository.findAllByUserIdAndProfile(anyString(), eq("default"), any(Pageable.class)))
                .thenReturn(new PageImpl<SessionEntity>(List.of()));
        service.listSessions("default", 50, 0, "foreign");
        verify(repository).findAllByUserIdAndProfile(eq("owner"), eq("default"), any(Pageable.class));
    }

    @Test
    void callerCannotCreateForAnotherOwner() {
        UserContext.set("owner", UserContext.ROLE_USER);
        Session session = mock(Session.class);
        when(session.id()).thenReturn(UUID.randomUUID());
        when(resolver.createSession(anyString(), anyString(), anyString(), eq("api_server"), eq("default"))).thenReturn(session);
        assertThat(service.createSession("default", "foreign", "noop", null)).containsEntry("user_id", "owner");
        verify(resolver).createSession("owner", "openai-compatible", "noop", "api_server", "default");
    }

    @Test
    void chatExistenceCheckRejectsForeignSessionBeforeAsyncDispatch() {
        UserContext.set("owner", UserContext.ROLE_USER);
        UUID id = UUID.randomUUID();
        SessionEntity foreign = new SessionEntity();
        foreign.setUserId("foreign");
        when(repository.findByIdAndProfile(id, "default")).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.sessionExists("default", id)).isInstanceOf(SecurityException.class);
    }

    @Test
    void chatExistenceCheckAllowsOwnAndRejectsMissingSession() {
        UserContext.set("owner", UserContext.ROLE_USER);
        UUID id = UUID.randomUUID();
        SessionEntity owned = new SessionEntity();
        owned.setUserId("owner");
        when(repository.findByIdAndProfile(id, "default")).thenReturn(Optional.of(owned));
        assertThat(service.sessionExists("default", id)).isTrue();
        assertThat(service.sessionExists("default", UUID.randomUUID())).isFalse();
    }
}
