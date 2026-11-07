package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Restarts delivery of durable review summaries after a bot restart.
 *
 * The backend owns the pending queue while StreamingOrchestrator owns the
 * idempotent Telegram send and acknowledgement transition. Scanning known
 * backend sessions means a review completed while the bot was down is not
 * stranded until the user sends another message.
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
