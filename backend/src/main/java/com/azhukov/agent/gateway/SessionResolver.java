package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Resolves or creates a Session for an inbound gateway message.
 * Separate Spring bean so @Transactional AOP proxy works.
 */
@Component
public class SessionResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionResolver.class);

    private final SessionRepository sessionRepository;
    private final AgentProperties properties;

    public SessionResolver(SessionRepository sessionRepository, AgentProperties properties) {
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    @Transactional
    public Session resolve(SessionSource source) {
        String userId = source.userId() != null ? source.userId() : source.chatId();
        SessionEntity existing = sessionRepository.findByUserId(userId);
        if (existing != null) {
            sessionRepository.touchUpdatedAt(existing.getId(), Instant.now());
            return new Session(existing.getId(), existing.getUserId(), existing.getTitle(),
                existing.getModelProvider(), existing.getModelName(), null, null);
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
        SessionEntity saved = sessionRepository.save(created);
        log.info("Created new session for userId={} sessionId={}", userId, saved.getId());
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, null);
    }
}