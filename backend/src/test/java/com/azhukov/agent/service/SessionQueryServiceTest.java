package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
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
        SessionRepository sessionRepository = mock(SessionRepository.class);
        when(sessionRepository.findAllByUserId(eq("user"), any(Pageable.class)))
            .thenAnswer(invocation -> new PageImpl<SessionEntity>(List.of(), invocation.getArgument(1), 1_000));
        SessionQueryService service = new SessionQueryService(
            sessionRepository,
            mock(MessageRepository.class),
            mock(AgentSessionResolver.class),
            mock(DomainDtoMapper.class),
            new AgentProperties(),
            mock(ApplicationEventPublisher.class));

        service.listSessions(50, 20, "user");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(sessionRepository).findAllByUserId(eq("user"), pageable.capture());
        assertThat(pageable.getValue().getOffset()).isEqualTo(20);
    }
}
