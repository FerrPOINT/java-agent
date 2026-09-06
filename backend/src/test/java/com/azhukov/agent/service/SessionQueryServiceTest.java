package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionQueryServiceTest {

    @Test
    void listSessionsUsesExactOffsetRatherThanRoundingToPageBoundary() {
        com.azhukov.agent.persistence.repository.SessionRepository sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        when(sessionRepository.findAllByUserId(eq("user"), any(Pageable.class)))
            .thenAnswer(invocation -> new PageImpl<SessionEntity>(List.of(), invocation.getArgument(1), 1_000));
        SessionQueryService service = new SessionQueryService(
            sessionRepository,
            mock(com.azhukov.agent.persistence.repository.MessageRepository.class),
            mock(AgentSessionResolver.class),
            mock(DomainDtoMapper.class),
            new AgentProperties(),
            mock(ApplicationEventPublisher.class));

        service.listSessions(50, 20, "user");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(sessionRepository).findAllByUserId(eq("user"), pageable.capture());
        assertThat(pageable.getValue().getOffset()).isEqualTo(20);
    }

    @Test
    void deleteSessionRejectsAnotherUsersSession() {
        com.azhukov.agent.persistence.repository.SessionRepository sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        SessionEntity session = new SessionEntity();
        session.setUserId("owner");
        java.util.UUID sessionId = java.util.UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(java.util.Optional.of(session));
        SessionQueryService service = new SessionQueryService(
            sessionRepository,
            mock(com.azhukov.agent.persistence.repository.MessageRepository.class),
            mock(AgentSessionResolver.class),
            mock(DomainDtoMapper.class),
            new AgentProperties(),
            mock(ApplicationEventPublisher.class));

        UserContext.set("other-user", UserContext.ROLE_USER);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteSession(sessionId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("does not belong");
        } finally {
            UserContext.clear();
        }
    }
}
