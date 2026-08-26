package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Orphan tool-result guard (Hermes background_review.py _digest_history): the
 * review snapshot must never START with a tool message — a tool result whose
 * assistant tool_call fell outside the tail window is an orphan that
 * Gemini/LiteLLM reject with "Missing corresponding tool call for tool
 * response message" (live incident: review retried forever on a memory-limit
 * error tool result, 2026-08-23).
 */
class BackgroundReviewOrphanGuardTest {

    private ModelClient modelClient;
    private AgentProperties properties;
    private BackgroundReviewService svc;

    @BeforeEach
    void setUp() {
        modelClient = Mockito.mock(ModelClient.class);
        properties = Mockito.mock(AgentProperties.class);
        AgentProperties.MemoryProperties memProps = Mockito.mock(AgentProperties.MemoryProperties.class);
        AgentProperties.BackgroundReviewProperties reviewProps = Mockito.mock(AgentProperties.BackgroundReviewProperties.class);
        when(properties.getMemory()).thenReturn(memProps);
        when(memProps.getBackgroundReview()).thenReturn(reviewProps);
        when(reviewProps.isEnabled()).thenReturn(true);
        when(reviewProps.getDelayMs()).thenReturn(0);
        when(reviewProps.getMaxReviewTurns()).thenReturn(2);
        svc = new BackgroundReviewService(modelClient, Mockito.mock(MemoryProvider.class),
            Mockito.mock(WriteApprovalGate.class), Mockito.mock(ReviewToolProvider.class), properties);
    }

    @AfterEach
    void clear() {
        WriteContext.clear();
    }

    private static void awaitModelCall(AtomicReference<List<Message>> sent, CountDownLatch latch) throws InterruptedException {
        latch.await(5, TimeUnit.SECONDS);
        assertThat(sent.get()).isNotEmpty();
    }

    @Test
    void snapshotNeverStartsWithToolMessage() throws InterruptedException {
        // capture the messages handed to the model
        AtomicReference<List<Message>> sent = new AtomicReference<>(List.of());
        CountDownLatch modelCallLatch = new CountDownLatch(1);
        when(modelClient.complete(anyList(), any()))
            .thenAnswer(inv -> {
                List<Message> msgs = (List<Message>) inv.getArgument(0);
                if (sent.get().isEmpty()) sent.set(new ArrayList<>(msgs));
                modelCallLatch.countDown();
                return ChatResponse.text("");
            });

        // 15-message history: the tail window (10) starts INSIDE a tool run —
        // first two entries are tool results whose assistant call is outside
        List<Message> history = new ArrayList<>();
        history.add(Message.user("старый вопрос"));
        history.add(Message.assistant("старый ответ с toolCalls", 0));
        for (int i = 0; i < 4; i++) {
            history.add(Message.toolResult("call_" + i, "result " + i, i));
        }
        for (int i = 0; i < 9; i++) {
            history.add(Message.user("q" + i));
            history.add(Message.assistant("a" + i, i));
        }
        // make the tail start with a tool result: append two tool results at the END boundary
        history.add(9, Message.toolResult("call_x", "orphan candidate", 0));
        history.add(10, Message.toolResult("call_y", "orphan candidate 2", 0));

        svc.reviewTurn(UUID.randomUUID(), history);
        awaitModelCall(sent, modelCallLatch);
        List<Message> snapshot = sent.get();
        assertThat(snapshot).isNotEmpty();
        // system prompt first, then the snapshot — NO leading tool role
        assertThat(snapshot.get(0).role().name()).isEqualTo("SYSTEM");
        for (int i = 1; i < Math.min(3, snapshot.size()); i++) {
            assertThat(snapshot.get(i).role().name()).as("message %s must not be TOOL", i).isNotEqualTo("TOOL");
        }
    }
}
