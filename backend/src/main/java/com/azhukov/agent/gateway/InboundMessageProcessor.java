package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class InboundMessageProcessor implements Consumer<MessageEvent> {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageProcessor.class);

    private final SessionRepository sessionRepository;
    private final AgentRuntime agentRuntime;
    private final ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private final AgentProperties properties;

    public InboundMessageProcessor(SessionRepository sessionRepository,
                                    AgentRuntime agentRuntime,
                                    ObjectProvider<GatewayRoutingService> routingServiceProvider,
                                    AgentProperties properties) {
        this.sessionRepository = sessionRepository;
        this.agentRuntime = agentRuntime;
        this.routingServiceProvider = routingServiceProvider;
        this.properties = properties;
    }

    @Override
    public void accept(MessageEvent event) {
        SessionSource source = event.source();
        log.info("Processing inbound {} message from userId={} chatId={} text={}",
            source.platform(), source.userId(), source.chatId(),
            event.text() != null ? event.text().substring(0, Math.min(event.text().length(), 80)) : "");

        Session session = resolveSession(source);
        var turnResult = agentRuntime.runTurn(session, event.text(), List.of());
        String response = turnResult.finalText();

        if (response == null || response.isBlank()) {
            response = "(пустой ответ от модели)";
        }

        routingServiceProvider.getIfAvailable().send(source.platform(), source, response)
            .whenComplete((result, ex) -> {
                if (ex != null || (result != null && !result.success())) {
                    log.warn("Failed to send response back to {}: {}", source.platform(),
                        ex != null ? ex.getMessage() : result.error());
                } else {
                    log.debug("Response sent back to {} userId={}", source.platform(), source.userId());
                }
            });
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Session resolveSession(SessionSource source) {
        String userId = source.userId() != null ? source.userId() : source.chatId();
        SessionEntity existing = sessionRepository.findByUserId(userId);
        if (existing != null) {
            return new Session(existing.getId(), existing.getUserId(), existing.getTitle(),
                existing.getModelProvider(), existing.getModelName(), null, null);
        }
        SessionEntity created = new SessionEntity();
        created.setId(UUID.randomUUID());
        created.setUserId(userId);
        created.setTitle("Telegram " + source.username());
        created.setModelProvider(properties.getModel().getProvider());
        created.setModelName(properties.getModel().getModelName());
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(created);
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, null);
    }
}
