package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.SessionStorePort;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * h12: JPA adapter implementing the core session-store port. Core classes
 * depend on {@link SessionStorePort}; this adapter is the only place that
 * knows about JPA.
 */
@Repository
@RequiredArgsConstructor
public class JpaSessionStore implements SessionStorePort {

    private final SessionRepository sessionRepository;

    @Override
    public SessionEntity save(SessionEntity entity) {
        return sessionRepository.save(entity);
    }

    @Override
    public Optional<SessionEntity> findById(UUID id) {
        return sessionRepository.findById(id);
    }

    @Override
    public void insertSessionRow(UUID id, String userId, String title, String provider,
                                 String modelName, String source, Instant now) {
        sessionRepository.insertSessionRow(id, userId, title, provider, modelName, source, now);
    }

    @Override
    public List<SessionEntity> findChildSessions(UUID parentSessionId) {
        return sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(parentSessionId);
    }
}
