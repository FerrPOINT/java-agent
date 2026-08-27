package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** P-05: new foreground work cancels scheduled/in-flight background review. */
class BackgroundReviewCancellationTest {

    @Test
    void foregroundCancellationBeforeDelayPreventsModelCall() throws Exception {
        ModelClient model = mock(ModelClient.class);
        ReviewToolProvider tools = mock(ReviewToolProvider.class);
        AgentProperties props = reviewProperties(120);
        BackgroundReviewService service = new BackgroundReviewService(model, mock(MemoryProvider.class),
            mock(WriteApprovalGate.class), tools, props);
        UUID sessionId = UUID.randomUUID();

        service.reviewTurn(sessionId, List.of(Message.user("hello")));
        service.cancelForNewForegroundTurn(sessionId);

        Thread.sleep(250);
        verifyNoInteractions(model, tools);
        service.shutdown();
    }

    @Test
    void cancellationBetweenModelAndReviewToolBlocksWrite() throws Exception {
        ModelClient model = mock(ModelClient.class);
        ReviewToolProvider tools = mock(ReviewToolProvider.class);
        AgentProperties props = reviewProperties(0);
        BackgroundReviewService service = new BackgroundReviewService(model, mock(MemoryProvider.class),
            mock(WriteApprovalGate.class), tools, props);
        UUID sessionId = UUID.randomUUID();
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);

        when(model.complete(any(), any())).thenAnswer(inv -> {
            modelEntered.countDown();
            assertThat(releaseModel.await(2, TimeUnit.SECONDS)).isTrue();
            return ChatResponse.toolCalls(List.of(new ToolCall("c1", "memory", "{}")));
        });

        service.reviewTurn(sessionId, List.of(Message.user("remember this")));
        assertThat(modelEntered.await(2, TimeUnit.SECONDS)).isTrue();
        service.cancelForNewForegroundTurn(sessionId);
        releaseModel.countDown();

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(model, times(1)).complete(any(), any()));
        verifyNoInteractions(tools);
        service.shutdown();
    }

    private static AgentProperties reviewProperties(int delayMs) {
        AgentProperties props = mock(AgentProperties.class);
        AgentProperties.MemoryProperties memory = mock(AgentProperties.MemoryProperties.class);
        AgentProperties.BackgroundReviewProperties review = mock(AgentProperties.BackgroundReviewProperties.class);
        when(props.getMemory()).thenReturn(memory);
        when(memory.getBackgroundReview()).thenReturn(review);
        when(review.isEnabled()).thenReturn(true);
        when(review.getDelayMs()).thenReturn(delayMs);
        when(review.getMaxReviewTurns()).thenReturn(2);
        return props;
    }
}
