package com.azhukov.agent.bot.goal;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GoalAutoContinueServiceTest {

    private GoalAutoContinueService serviceWith(AgentBackendClient client, BotProperties properties) {
        return new GoalAutoContinueService(client, properties);
    }

    private BotSessionEntity sessionWithGoal(String goal) {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", goal);
        return session;
    }

    @Test
    void noGoalReturnsEmptyList() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        BotProperties properties = new BotProperties();
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        List<String> result = service.runAutoContinue(session, "hello");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void disabledReturnsEmptyListEvenWithGoal() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(false);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void maxTurnsZeroReturnsEmptyList() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(0);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void judgeSaysYes_noContinuation() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "done");

        assertThat(result).isEmpty();
        verify(client, times(1)).chat(anyString(), isNull(), isNull());
    }

    @Test
    void judgeSaysNo_runsContinueTurn() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        // Judge call (null sessionId): first NO, then YES (after continuation)
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("Deployment completed successfully."));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working on it");

        assertThat(result).containsExactly("Deployment completed successfully.");
    }

    @Test
    void maxTurnsRespected() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("still working"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(2);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working");

        assertThat(result).hasSize(2);
    }

    @Test
    void backendErrorStopsContinuation() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("Error: connection refused"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(5);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working");

        assertThat(result).isEmpty();
    }

    @Test
    void judgeErrorAssumesNotComplete() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        // Judge call fails with error on first iteration, then succeeds with YES on second
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("Error: timeout"))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("Done."));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "working");

        // Judge error → not complete → one continuation turn → judge says YES on next iteration
        assertThat(result).containsExactly("Done.");
    }

    @Test
    void interruptStopsLoop() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("step 1"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(5);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");

        // Interrupt after first continuation
        AtomicBoolean interrupted = new AtomicBoolean(false);
        List<String> result = service.runAutoContinue(session, "working", () -> {
            if (interrupted.get()) return true;
            interrupted.set(true);
            return false;
        });

        // Should produce exactly 1 continuation, then interrupt on next iteration
        assertThat(result).hasSize(1);
        assertThat(result).containsExactly("step 1");
    }

    @Test
    void nullLastResponseHandled() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, null);

        assertThat(result).isEmpty();
    }

    @Test
    void noSubstringCompletionHeuristic() {
        // The substring heuristic was removed — judge is the sole evaluator.
        // "I'm not done yet" should NOT cause early termination.
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"));
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("I'm not done yet, still working on it"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(2);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        List<String> result = service.runAutoContinue(session, "starting");

        // Both turns should run — no substring heuristic to cause early break
        assertThat(result).hasSize(2);
    }

    @Test
    void judgeCallUsesNullSessionId() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), isNull(), isNull()))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = sessionWithGoal("deploy app");
        session.setId(UUID.randomUUID());

        service.runAutoContinue(session, "done");

        // Verify judge call uses null sessionId and null runtime (not session)
        verify(client).chat(anyString(), isNull(), isNull());
    }

    @Test
    void pausedGoalReturnsEmpty() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = serviceWith(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "deploy app");
        session.setMetadata("_goalPaused", "true");

        List<String> result = service.runAutoContinue(session, "working");

        // getActiveGoal returns null for paused goals
        assertThat(result).isEmpty();
    }
}