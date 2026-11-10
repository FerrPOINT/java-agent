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

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final ObjectProvider<com.azhukov.agent.core.agent.InterruptToken> interruptTokenProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.AttachmentArtifactService>
        attachmentArtifacts;

    // rev-83: subagent protection (#30170 parity) — demote interrupt to queue
    // when the running agent has active subagents, so a conversational follow-up
    // doesn't destroy minutes of subagent work.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.azhukov.agent.tools.delegate.DelegateTaskTool delegateTaskTool;

    /** Each entry owns the chat until its current message and any queued messages finish. */
    private final ConcurrentHashMap<String, ChatQueue> activeChats = new ConcurrentHashMap<>();

    private static final class ChatQueue {
        private final ArrayDeque<MessageEvent> pending = new ArrayDeque<>();
        private UUID runningSession;
    }

    /**
     * Bounded dedup of platform redeliveries: Telegram retries webhook POSTs
     * and can redeliver long-poll updates after reconnect. Keyed by
     * "platform:eventId"; null eventIds (synthetic events, late-steer
     * handoffs) never dedup. Bounded to avoid an unbounded leak.
     */
    private static final int DEDUP_MAX_ENTRIES = 10_000;
    private final java.util.Set<String> seenEventIds =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_MAX_ENTRIES;
                }
            }));

    private boolean isDuplicateRedelivery(MessageEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            return false;
        }
        String key = event.source().platform() + ":" + event.eventId();
        // add() returns false when already present → duplicate redelivery
        return !seenEventIds.add(key);
    }

    private com.azhukov.agent.core.agent.SessionTurnLockManager turnLocks =
        new com.azhukov.agent.core.agent.SessionTurnLockManager();

    @org.springframework.beans.factory.annotation.Autowired
    void setTurnLocks(com.azhukov.agent.core.agent.SessionTurnLockManager turnLocks) {
        this.turnLocks = turnLocks;
    }

    @Override
    public void accept(MessageEvent event) {
        process(event, null);
    }

    private void process(MessageEvent event, ChatQueue ownedQueue) {
        SessionSource source = event.source();
        log.info("Processing inbound {} message from userId={} chatId={} text={}",
            source.platform(), source.userId(), source.chatId(),
            event.text() != null ? event.text().substring(0, Math.min(event.text().length(), 80)) : "");

        String chatId = source.chatId();
        boolean newDelivery = ownedQueue == null;
        UUID lockedSession = null;
        try {
            if (!isAuthorized(source)) {
                log.warn("Skipping unauthorized inbound message from platform={} userId={} chatId={}",
                    source.platform(), source.userId(), source.chatId());
                return;
            }

            // Platform redelivery dedup (Telegram retries webhook POSTs on
            // timeout/5xx): a repeated eventId is a redelivery, not a new message.
            if (newDelivery && isDuplicateRedelivery(event)) {
                log.info("Skipping duplicate redelivery: platform={} eventId={}",
                    source.platform(), event.eventId());
                return;
            }

            // Send typing indicator before LLM call (Telegram shows it for ~5s)
            routingServiceProvider.getIfAvailable().sendTyping(source.platform(), source);
            Session session = sessionResolver.resolve(source);

            if (newDelivery) {
                var queue = new ChatQueue();
                ChatQueue active;
                while ((active = activeChats.putIfAbsent(chatId, queue)) != null) {
                    if (handleBusy(chatId, active, session, event)) {
                        return;
                    }
                }
                ownedQueue = queue;
            }

            if (!turnLocks.tryAcquire(session.id(), 30)) {
                throw new IllegalStateException("Session is busy: " + session.id());
            }
            lockedSession = session.id();
            synchronized (ownedQueue) {
                ownedQueue.runningSession = lockedSession;
            }

            // P1-5: When mid-turn persistence is active, persist only the user message
            // before the turn. The DefaultAgentRuntime persists assistant + tool messages
            // mid-turn via MidTurnPersistenceCallback. Skip full persistTurn to avoid duplicates.
            if (midTurnPersistenceCallback != null) {
                messagePersistenceService.persistUserMessage(session, event.text());
            }

            var turnResult = agentRuntime.runTurn(session, event.text(),
                registerAttachments(session, event, source));

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
                synchronized (ownedQueue) {
                    ownedQueue.pending.add(new MessageEvent(UUID.randomUUID().toString(), source,
                        event.type(), lateSteer, List.of(), java.util.Map.of("steer_handoff", "true"),
                        java.time.Instant.now()));
                }
                log.info("steer_handoff_queued_after_turn session={}", session.id());
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
            if (lockedSession != null) {
                synchronized (ownedQueue) {
                    ownedQueue.runningSession = null;
                }
                turnLocks.release(lockedSession);
            }
            if (ownedQueue != null) {
                dispatchNext(chatId, ownedQueue);
            }
        }
    }

    private boolean handleBusy(String chatId, ChatQueue queue, Session session, MessageEvent event) {
        String mode = agentProperties.getGateway().getBusyInputMode();
        boolean interrupt = "interrupt".equalsIgnoreCase(mode);
        boolean protectSubagents = interrupt && delegateTaskTool != null && delegateTaskTool.getActiveSubagentCount() > 0;
        String reply;
        synchronized (queue) {
            if (activeChats.get(chatId) != queue) {
                return false;
            }
            boolean ownsTurn = session.id() != null && session.id().equals(queue.runningSession);
            if (ownsTurn && "steer".equalsIgnoreCase(mode) && steerBuffer != null
                    && steerBuffer.steer(session.id(), event.text())) {
                reply = "⏩ Steered into current run. Your message arrives after the next tool call.";
            } else {
                queue.pending.add(event);
                if (ownsTurn && interrupt && !protectSubagents) {
                    if (interruptTokenProvider != null) {
                        var token = interruptTokenProvider.getIfAvailable();
                        if (token != null) token.cancel(session.id());
                    }
                    reply = "⚡ Interrupting current task. I'll respond to your message shortly.";
                } else if (ownsTurn && protectSubagents) {
                    reply = "⏳ Queued for the next turn. Active subagents detected — I'll respond once they finish.";
                } else {
                    reply = "⏳ Queued for the next turn. I'll respond once the current task finishes.";
                }
            }
        }
        var source = event.source();
        routingServiceProvider.getIfAvailable().send(source.platform(), source, reply);
        return true;
    }

    private void dispatchNext(String chatId, ChatQueue queue) {
        MessageEvent next;
        synchronized (queue) {
            next = queue.pending.poll();
            if (next == null) {
                activeChats.remove(chatId, queue);
                return;
            }
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> process(next, queue));
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

    /**
     * WP-f: register inbound gateway attachments as backend artifacts and
     * return their cache paths as turn references. MessageEvent.attachments
     * was previously a dead field nothing read; the reference lane loads the
     * cached file content into the turn exactly like the bot's artifact path.
     * Best-effort: on any failure the turn runs attachment-free rather than
     * failing the user's message.
     */
    private List<String> registerAttachments(com.azhukov.agent.core.model.Session session, MessageEvent event,
                                             com.azhukov.agent.gateway.model.SessionSource source) {
        if (event.attachments() == null || event.attachments().isEmpty()) {
            return List.of();
        }
        com.azhukov.agent.service.AttachmentArtifactService artifacts =
            attachmentArtifacts == null ? null : attachmentArtifacts.getIfAvailable();
        if (artifacts == null) {
            return List.of();
        }
        List<String> references = new java.util.ArrayList<>();
        for (MessageEvent.Attachment att : event.attachments()) {
            if (att == null || att.data() == null || att.data().length == 0) {
                continue;
            }
            try {
                String name = att.fileName() != null && !att.fileName().isBlank()
                    ? att.fileName() : "gateway-file.bin";
                var registration = artifacts.register(
                    source.userId() != null ? source.userId() : "gateway",
                    "default",
                    session != null ? session.id() : null,
                    event.eventId(),
                    source.platform().name().toLowerCase(java.util.Locale.ROOT),
                    dispositionOf(att.mimeType()),
                    att.mimeType(),
                    name,
                    att.data());
                if (registration != null && registration.id() != null && registration.cachePath() != null) {
                    references.add(registration.cachePath());
                } else if (registration != null && registration.rejection() != null) {
                    log.debug("gateway attachment rejected: {}", registration.rejection());
                }
            } catch (Exception e) {
                // One bad attachment must not kill the message.
                log.debug("gateway attachment registration failed: {}", e.getMessage());
            }
        }
        return references;
    }

    private static String dispositionOf(String mimeType) {
        if (mimeType == null) return "file";
        if (mimeType.startsWith("image/")) return "photo";
        if (mimeType.startsWith("audio/")) return "voice";
        if (mimeType.startsWith("video/")) return "video";
        return "file";
    }
}
