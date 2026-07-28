package com.azhukov.agent.gateway;

import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Consumer;

public class InboundMessageProcessor implements Consumer<MessageEvent> {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageProcessor.class);

    private final SessionResolver sessionResolver;
    private final AgentRuntime agentRuntime;
    private final ObjectProvider<GatewayRoutingService> routingServiceProvider;

    public InboundMessageProcessor(SessionResolver sessionResolver,
                                    AgentRuntime agentRuntime,
                                    ObjectProvider<GatewayRoutingService> routingServiceProvider) {
        this.sessionResolver = sessionResolver;
        this.agentRuntime = agentRuntime;
        this.routingServiceProvider = routingServiceProvider;
    }

    @Override
    public void accept(MessageEvent event) {
        SessionSource source = event.source();
        log.info("Processing inbound {} message from userId={} chatId={} text={}",
            source.platform(), source.userId(), source.chatId(),
            event.text() != null ? event.text().substring(0, Math.min(event.text().length(), 80)) : "");

        if (!isAuthorized(source)) {
            log.warn("Skipping unauthorized inbound message from platform={} userId={} chatId={}",
                source.platform(), source.userId(), source.chatId());
            return;
        }

        try {
            // Send typing indicator before LLM call (Telegram shows it for ~5s)
            routingServiceProvider.getIfAvailable().sendTyping(source.platform(), source);
            Session session = sessionResolver.resolve(source);
            var turnResult = agentRuntime.runTurn(session, event.text(), List.of());
            String response = turnResult.finalText();

            if (response == null || response.isBlank()) {
                response = "(пустой ответ от модели)";
            }

            final String reply = response;
            routingServiceProvider.getIfAvailable().send(source.platform(), source, reply)
                .whenComplete((result, ex) -> {
                    if (ex != null || (result != null && !result.success())) {
                        log.warn("Failed to send response back to {}: {}", source.platform(),
                            ex != null ? ex.getMessage() : result.error());
                    } else {
                        log.debug("Response sent back to {} userId={}", source.platform(), source.userId());
                    }
                });
        } catch (Exception e) {
            log.error("Error processing inbound message from userId={}: {}", source.userId(), e.getMessage(), e);
            try {
                routingServiceProvider.getIfAvailable().send(source.platform(), source,
                    "Ошибка обработки: " + e.getMessage());
            } catch (Exception sendEx) {
                log.error("Failed to send error reply to userId={}: {}", source.userId(), sendEx.getMessage());
            }
        }
    }

    private boolean isAuthorized(SessionSource source) {
        return source.platform() == Platform.TELEGRAM;
    }
}