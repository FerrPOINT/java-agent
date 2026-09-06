package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.SessionEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port (h12): the narrow slice of session persistence the agent
 * core needs. Implemented by the JPA {@code SessionRepository}; keeping the
 * surface small is what lets core stay JPA-free and makes the multi-user
 * filtering (mu5) enforceable in one place.
 */
public interface SessionStorePort {

    SessionEntity save(SessionEntity entity);

    Optional<SessionEntity> findById(UUID id);

    void insertSessionRow(UUID id, String userId, String title, String provider,
                          String modelName, String source, Instant now);

    List<SessionEntity> findChildSessions(UUID parentSessionId);
}
