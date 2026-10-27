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
    private com.azhukov.agent.core.ports.IdentityReferenceRegistration identityReferences;

    @org.springframework.beans.factory.annotation.Autowired
    void configureIdentityReferences(org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.core.ports.IdentityReferenceRegistration> provider) {
        identityReferences=provider.getIfAvailable();
    }

    @Transactional
    public Session resolve(SessionSource source) {
        String userId = source.userId() != null ? source.userId() : source.chatId();
        // The authenticated Telegram adapter supplies native platform ids. It
        // does not run under an HTTP human context, and must retain that kind.
        if(identityReferences!=null && source.platform()==com.azhukov.agent.gateway.model.Platform.TELEGRAM
            && userId!=null && userId.matches("-?[0-9]{1,20}")) {
            var previous=com.azhukov.agent.core.security.NativeIdentityOrigin.current();
            try {
                com.azhukov.agent.core.security.NativeIdentityOrigin.bind(new com.azhukov.agent.core.security.NativeIdentityOrigin.Origin(
                    com.azhukov.agent.core.security.NativeIdentityOrigin.Kind.GATEWAY_EVENT,userId,false));
                identityReferences.nativeStorage(userId);
            } finally {com.azhukov.agent.core.security.NativeIdentityOrigin.bind(previous);}
        }
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
        created.setOriginPlatform(source.platform() == null ? null : source.platform().name().toLowerCase(java.util.Locale.ROOT));
        created.setOriginChatId(trimToNull(source.chatId()));
        created.setOriginThreadId(trimToNull(source.threadId()));
        created.setOriginUserId(trimToNull(source.userId()));
        created.setLastActive(Instant.now());
        created.setMessageCount(0);
        SessionEntity saved = sessionRepository.save(created);
        log.info("Created new session for userId={} sessionId={}", userId, saved.getId());
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, java.util.Map.of(), saved.getSubgoal());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
