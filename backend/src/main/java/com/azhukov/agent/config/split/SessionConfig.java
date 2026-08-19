package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.InboundMessageProcessor;
import com.azhukov.agent.gateway.SessionResolver;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.persistence.MessagePersistenceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.function.Consumer;

/**
 * Gateway/session-related beans: {@link InboundMessageProcessor} (as the
 * {@code gatewayMessageHandler} bean) and {@link GatewayRoutingService}.
 */
@Configuration
public class SessionConfig {

    @Bean(name = "gatewayMessageHandler")
    public Consumer<MessageEvent> gatewayMessageHandler(
            SessionResolver sessionResolver,
            AgentRuntime agentRuntime,
            ObjectProvider<GatewayRoutingService> routingServiceProvider,
            MessagePersistenceService messagePersistenceService,
            MidTurnPersistenceCallback midTurnPersistenceCallback,
            AgentProperties agentProperties,
            SteerBuffer steerBuffer) {
        return new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider,
            messagePersistenceService, midTurnPersistenceCallback, agentProperties, steerBuffer);
    }

    @Bean
    @ConditionalOnMissingBean(GatewayRoutingService.class)
    public GatewayRoutingService gatewayRoutingService(List<BasePlatformAdapter> adapters,
            Consumer<MessageEvent> gatewayMessageHandler) {
        return new GatewayRoutingService(adapters, gatewayMessageHandler);
    }
}