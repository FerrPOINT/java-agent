package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls backend review summaries that have not yet been claimed.
 * Claiming happens before best-effort notification, so a crash after the claim
 * can lose the notification; it must not be replayed as a confirmed delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewDeliveryRecoveryPoller {

    private final BotSessionRepository sessionRepository;
    private final StreamingOrchestrator streamingOrchestrator;
    private final BotMessageProcessor messageProcessor;

    @Scheduled(fixedDelay = 20_000L, initialDelay = 20_000L)
    public void poll() {
        for (BotSessionEntity session : sessionRepository.findByBackendSessionIdIsNotNull()) {
            if (session.getBackendSessionId() == null || session.getChatId() == null) {
                continue;
            }
            try {
                long chatId = Long.parseLong(session.getChatId());
                streamingOrchestrator.deliverPendingReview(
                    session.getBackendSessionId().toString(),
                    chatId,
                    0L,
                    session.getLastMessageThreadId(),
                    messageProcessor);
            } catch (NumberFormatException e) {
                log.debug("Review delivery skipped for bot session {}: invalid chat id", session.getId());
            } catch (Exception e) {
                log.debug("Review delivery recovery failed for bot session {}: {}",
                    session.getId(), e.getMessage());
            }
        }
    }
}
