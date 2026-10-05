package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewDeliveryRecoveryPollerTest {

    @Test
    void pollRedrivesPendingReviewForPersistedBackendSession() {
        BotSessionRepository repository = mock(BotSessionRepository.class);
        StreamingOrchestrator orchestrator = mock(StreamingOrchestrator.class);
        BotMessageProcessor processor = mock(BotMessageProcessor.class);
        BotSessionEntity session = new BotSessionEntity();
        UUID backendSessionId = UUID.randomUUID();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(backendSessionId);
        session.setChatId("100200300");
        session.setLastMessageThreadId(17585L);
        when(repository.findByBackendSessionIdIsNotNull()).thenReturn(List.of(session));

        new ReviewDeliveryRecoveryPoller(repository, orchestrator, processor).poll();

        verify(orchestrator).deliverPendingReview(
            backendSessionId.toString(), 100200300L, 0L, 17585L, processor);
    }

    @Test
    void pollSkipsInvalidChatId() {
        BotSessionRepository repository = mock(BotSessionRepository.class);
        StreamingOrchestrator orchestrator = mock(StreamingOrchestrator.class);
        BotMessageProcessor processor = mock(BotMessageProcessor.class);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        session.setChatId("invalid");
        when(repository.findByBackendSessionIdIsNotNull()).thenReturn(List.of(session));

        new ReviewDeliveryRecoveryPoller(repository, orchestrator, processor).poll();

        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }
}
