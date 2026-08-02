package com.azhukov.agent.bot.goal;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GoalAutoContinueServiceTest {

    @Test
    void noGoalReturnsEmptyList() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        BotProperties properties = new BotProperties();
        GoalAutoContinueService service = new GoalAutoContinueService(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        List<String> result = service.runAutoContinue(session, "hello");

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void judgeSaysYes_noContinuation() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("YES"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = new GoalAutoContinueService(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "deploy app");

        List<String> result = service.runAutoContinue(session, "done");

        assertThat(result).isEmpty();
        verify(client, times(1)).chat(anyString(), anyString(), any());
    }

    @Test
    void judgeSaysNo_runsContinueTurn() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"))
            .thenReturn(new AgentBackendClient.ChatResult("Deployment completed successfully."));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(3);
        GoalAutoContinueService service = new GoalAutoContinueService(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "deploy app");

        List<String> result = service.runAutoContinue(session, "working on it");

        assertThat(result).containsExactly("Deployment completed successfully.");
        verify(client, times(2)).chat(anyString(), anyString(), any());
    }

    @Test
    void maxTurnsRespected() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.chat(anyString(), anyString(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("NO"))
            .thenReturn(new AgentBackendClient.ChatResult("still working"))
            .thenReturn(new AgentBackendClient.ChatResult("NO"))
            .thenReturn(new AgentBackendClient.ChatResult("still working"));

        BotProperties properties = new BotProperties();
        properties.getGoalAutoContinue().setEnabled(true);
        properties.getGoalAutoContinue().setMaxTurns(2);
        GoalAutoContinueService service = new GoalAutoContinueService(client, properties);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "deploy app");

        List<String> result = service.runAutoContinue(session, "working");

        assertThat(result).hasSize(2);
    }
}
