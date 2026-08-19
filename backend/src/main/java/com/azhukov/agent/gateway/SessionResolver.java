package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Resolves or creates a Session for an inbound gateway message.
 * Separate Spring bean so @Transactional AOP proxy works.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionResolver {

    private final SessionRepository sessionRepository;
    private final AgentProperties properties;

    @Transactional
    public Session resolve(SessionSource source) {
        String userId = source.userId() != null ? source.userId() : source.chatId();
        SessionEntity existing = sessionRepository.findByUserId(userId);
        if (existing != null) {
            sessionRepository.touchUpdatedAt(existing.getId(), Instant.now());
            return new Session(existing.getId(), existing.getUserId(), existing.getTitle(),
                existing.getModelProvider(), existing.getModelName(), null, java.util.Map.of(), existing.getSubgoal());
        }
        // Don't set id manually — let @GeneratedValue produce it.
        // Setting id manually makes Hibernate think the entity is detached → merge() → StaleObjectStateException.
        SessionEntity created = new SessionEntity();
        created.setUserId(userId);
        created.setTitle("Telegram " + source.username());
        created.setModelProvider(properties.getModel().getProvider());
        created.setModelName(properties.getModel().getModelName());
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        created.setSource("telegram");
        created.setLastActive(Instant.now());
        created.setMessageCount(0);
        SessionEntity saved = sessionRepository.save(created);
        log.info("Created new session for userId={} sessionId={}", userId, saved.getId());
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, java.util.Map.of(), saved.getSubgoal());
    }
}