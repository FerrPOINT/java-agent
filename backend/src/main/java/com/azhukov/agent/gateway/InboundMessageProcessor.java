package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.MidTurnPersistenceCallback;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.service.MessagePersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class InboundMessageProcessor implements Consumer<MessageEvent> {

    private final SessionResolver sessionResolver;
    private final AgentRuntime agentRuntime;
    private final ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private final MessagePersistenceService messagePersistenceService;
    private final MidTurnPersistenceCallback midTurnPersistenceCallback;
    private final AgentProperties agentProperties;
    private final SteerBuffer steerBuffer;

    /** Tracks active sessions per chat to detect busy state. */
    private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();

    /** Per-session queue for messages arriving while busy in "queue" mode. */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<MessageEvent>> pendingQueues = new ConcurrentHashMap<>();

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

            // Check busy-input mode for mid-run message handling
            String busyInputMode = agentProperties.getGateway().getBusyInputMode();
            String sessionKey = source.chatId();
            boolean isBusy = activeSessions.containsKey(sessionKey);

            if (isBusy && "steer".equalsIgnoreCase(busyInputMode)) {
                // Steer mode: inject mid-run via SteerBuffer
                if (steerBuffer != null && session.id() != null) {
                    boolean steered = steerBuffer.steer(session.id(), event.text());
                    if (steered) {
                        log.info("Steered message for session {}", session.id());
                        routingServiceProvider.getIfAvailable().send(source.platform(), source,
                            "⏩ Steered into current run. Your message arrives after the next tool call.");
                        return;
                    }
                }
                // Fall back to queue if steer failed
                log.debug("Steer failed, falling back to queue for session {}", session.id());
                routingServiceProvider.getIfAvailable().send(source.platform(), source,
                    "⏳ Queued for the next turn. I'll respond once the current task finishes.");
                return;
            } else if (isBusy && "interrupt".equalsIgnoreCase(busyInputMode)) {
                // L1: Interrupt mode — cancel the current turn and queue the message
                log.debug("Interrupt mode for busy session {} — queuing and cancelling", session.id());
                pendingQueues.computeIfAbsent(sessionKey, k -> new ConcurrentLinkedQueue<>()).add(event);
                routingServiceProvider.getIfAvailable().send(source.platform(), source,
                    "⚡ Interrupting current task. I'll respond to your message shortly.");
                // The runtime checks interruptToken.isCancelled() between tool calls.
                // The queued message will be processed after the current turn is interrupted
                // and drained via the finally block below.
                return;
            } else if (isBusy && "queue".equalsIgnoreCase(busyInputMode)) {
                // Queue mode: store the message for processing after the current turn completes
                log.debug("Queueing message for busy session {}", session.id());
                pendingQueues.computeIfAbsent(sessionKey, k -> new ConcurrentLinkedQueue<>()).add(event);
                routingServiceProvider.getIfAvailable().send(source.platform(), source,
                    "⏳ Queued for the next turn. I'll respond once the current task finishes.");
                return;
            }

            // P1-5: When mid-turn persistence is active, persist only the user message
            // before the turn. The DefaultAgentRuntime persists assistant + tool messages
            // mid-turn via MidTurnPersistenceCallback. Skip full persistTurn to avoid duplicates.
            if (midTurnPersistenceCallback != null) {
                messagePersistenceService.persistUserMessage(session, event.text());
            }

            activeSessions.put(sessionKey, true);
            var turnResult = agentRuntime.runTurn(session, event.text(), List.of());

            // Persist user input + assistant response so context engine can load history on next turn
            // P1-5: Skip end-of-turn persistence when mid-turn persistence is active
            // (messages were already persisted during the turn by MidTurnPersistenceCallback)
            if (midTurnPersistenceCallback == null) {
                messagePersistenceService.persistTurn(session, event.text(), turnResult);
            }

            String response = turnResult.finalText();

            // P8 parity (turn_finalizer.py:756): a steer received after the
            // turn's last model boundary becomes the next user event instead of
            // being silently cleared from SteerBuffer.
            String lateSteer = turnResult.pendingSteer();
            if (lateSteer != null && !lateSteer.isBlank()) {
                pendingQueues.computeIfAbsent(sessionKey, k -> new ConcurrentLinkedQueue<>()).add(
                    new MessageEvent(java.util.UUID.randomUUID().toString(), source,
                        event.type(), lateSteer, List.of(), java.util.Map.of("steer_handoff", "true"),
                        java.time.Instant.now()));
                log.info("Queued late steer handoff for session {}", session.id());
            }

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
        } finally {
            activeSessions.remove(source.chatId());
            // Drain pending queue: process any messages that arrived during the current turn
            ConcurrentLinkedQueue<MessageEvent> queue = pendingQueues.remove(source.chatId());
            if (queue != null && !queue.isEmpty()) {
                MessageEvent next = queue.poll();
                if (next != null) {
                    log.debug("Draining queued message for chat {}", source.chatId());
                    // Re-enqueue any remaining messages so they are processed in order
                    if (!queue.isEmpty()) {
                        ConcurrentLinkedQueue<MessageEvent> remaining = pendingQueues.computeIfAbsent(
                            source.chatId(), k -> new ConcurrentLinkedQueue<>());
                        remaining.addAll(queue);
                    }
                    // Process the next queued message asynchronously
                    java.util.concurrent.CompletableFuture.runAsync(() -> accept(next));
                }
            }
        }
    }

    private boolean isAuthorized(SessionSource source) {
        // Non-Telegram platforms are not gated by Telegram config — let them through
        if (source.platform() != Platform.TELEGRAM) {
            return true;
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