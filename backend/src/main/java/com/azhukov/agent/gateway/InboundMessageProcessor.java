package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.MessagePersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class InboundMessageProcessor implements Consumer<MessageEvent> {

    private final SessionResolver sessionResolver;
    private final AgentRuntime agentRuntime;
    private final ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private final MessagePersistenceService messagePersistenceService;
    private final AgentProperties agentProperties;

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

            // Persist user input + assistant response so context engine can load history on next turn
            messagePersistenceService.persistTurn(session, event.text(), turnResult);

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
        // Non-Telegram platforms are not gated by Telegram config
        if (source.platform() != Platform.TELEGRAM) {
            return false;
        }
        var telegram = agentProperties.getGateway().getTelegram();
        // allowByDefault → open access
        if (telegram.isAllowByDefault()) {
            return true;
        }
        String userId = source.userId();
        if (userId != null && !userId.isBlank()) {
            for (String allowed : telegram.getAllowedUserIds()) {
                if (userId.equals(allowed)) {
                    return true;
                }
            }
        }
        String username = source.username();
        if (username != null && !username.isBlank()) {
            for (String allowed : telegram.getAllowedUsernames()) {
                if (username.equals(allowed)) {
                    return true;
                }
            }
        }
        return false;
    }
}