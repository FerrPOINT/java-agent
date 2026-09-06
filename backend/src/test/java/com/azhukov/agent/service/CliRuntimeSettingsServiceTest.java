package com.azhukov.agent.service;

import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliRuntimeSettingsServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private SessionRepository sessionRepository;
    private ToolRegistry toolRegistry;
    private CliRuntimeSettingsService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(com.azhukov.agent.persistence.repository.SessionRepository.class);
        toolRegistry = mock(ToolRegistry.class);
        service = new CliRuntimeSettingsService(sessionRepository, toolRegistry);
    }

    private SessionEntity givenSession() {
        SessionEntity e = new SessionEntity();
        e.setId(SESSION_ID);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void setReasoningEffortStoresInCliState() {
        SessionEntity e = givenSession();

        service.setReasoningEffort(SESSION_ID, "high");

        assertThat(e.getCliStateValue("reasoningEffort")).isEqualTo("high");
    }

    @Test
    void setFastModeStoresInCliState() {
        SessionEntity e = givenSession();

        service.setFastMode(SESSION_ID, true);

        assertThat(e.getCliStateValue("fastMode")).isEqualTo("true");
    }

    @Test
    void toggleFastModeReturnsRequestedState() {
        givenSession();

        assertThat(service.toggleFastMode(SESSION_ID, true)).isTrue();
        assertThat(service.toggleFastMode(SESSION_ID, false)).isFalse();
    }

    @Test
    void setVoiceModeStoresInCliState() {
        SessionEntity e = givenSession();

        service.setVoiceMode(SESSION_ID, false);

        assertThat(e.getCliStateValue("voiceMode")).isEqualTo("false");
    }

    @Test
    void setPersonalityStoresInCliState() {
        SessionEntity e = givenSession();

        service.setPersonality(SESSION_ID, "concise");

        assertThat(e.getCliStateValue("personality")).isEqualTo("concise");
    }

    @Test
    void setSubgoalStoresInSubgoalField() {
        SessionEntity e = givenSession();

        service.setSubgoal(SESSION_ID, "verify cli");

        assertThat(e.getSubgoal()).isEqualTo("verify cli");
    }

    @Test
    void setTitleStoresInTitleField() {
        SessionEntity e = givenSession();

        service.setTitle(SESSION_ID, "test-run");

        assertThat(e.getTitle()).isEqualTo("test-run");
    }

    @Test
    void setQueuedPromptStoresInCliState() {
        SessionEntity e = givenSession();

        service.setQueuedPrompt(SESSION_ID, "hello");

        assertThat(e.getCliStateValue("queuedPrompt")).isEqualTo("hello");
    }

    @Test
    void setCdpUrlStoresInCliState() {
        SessionEntity e = givenSession();

        service.setCdpUrl(SESSION_ID, "http://localhost:9222");

        assertThat(e.getCliStateValue("cdpUrl")).isEqualTo("http://localhost:9222");
    }

    @Test
    void disableToolAddsToDisabledTools() {
        SessionEntity e = givenSession();

        service.disableTool(SESSION_ID, "write_file");

        assertThat(e.getCliStateValue("disabledTools")).isEqualTo("write_file");
    }

    @Test
    void enableToolRemovesFromDisabledTools() {
        SessionEntity e = givenSession();
        e.setCliStateValue("disabledTools", "read_file,write_file");

        service.enableTool(SESSION_ID, "write_file");

        assertThat(e.getCliStateValue("disabledTools")).isEqualTo("read_file");
    }

    @Test
    void enableToolClearsWhenNoDisabledLeft() {
        SessionEntity e = givenSession();
        e.setCliStateValue("disabledTools", "write_file");

        service.enableTool(SESSION_ID, "write_file");

        assertThat(e.getCliStateValue("disabledTools")).isNull();
    }

    @Test
    void listToolNamesReturnsSortedNames() {
        when(toolRegistry.getDefinitions()).thenReturn(List.of(
            new ToolDefinition("write_file", "write", Map.of()),
            new ToolDefinition("read_file", "read", Map.of())
        ));

        List<String> names = service.listToolNames();

        assertThat(names).containsExactly("read_file", "write_file");
    }

    @Test
    void clearGoalRemovesGoalKeysFromCliState() {
        SessionEntity e = givenSession();
        e.setCliStateValue("goal", "fix all bugs");
        e.setCliStateValue("goalPaused", "true");
        e.setCliStateValue("subgoals", "bug1\nbug2\nbug3");

        service.clearGoal(SESSION_ID);

        // Keys must be truly absent, not just null-valued
        assertThat(e.getCliState()).doesNotContainKey("goal");
        assertThat(e.getCliState()).doesNotContainKey("goalPaused");
        assertThat(e.getCliState()).doesNotContainKey("subgoals");
        assertThat(e.getCliStateValue("goal")).isNull();
        assertThat(e.getCliStateValue("goalPaused")).isNull();
        assertThat(e.getCliStateValue("subgoals")).isNull();
    }

    @Test
    void clearSubgoalsRemovesSubgoalsKeyFromCliState() {
        SessionEntity e = givenSession();
        e.setCliStateValue("subgoals", "task A\ntask B");

        service.clearSubgoals(SESSION_ID);

        assertThat(e.getCliState()).doesNotContainKey("subgoals");
        assertThat(e.getCliStateValue("subgoals")).isNull();
    }

    @Test
    void setGoalStoresInCliState() {
        SessionEntity e = givenSession();

        service.setGoal(SESSION_ID, "ship the release");

        assertThat(e.getCliStateValue("goal")).isEqualTo("ship the release");
    }

    @Test
    void setGoalPausedStoresInCliState() {
        SessionEntity e = givenSession();

        service.setGoalPaused(SESSION_ID, true);

        assertThat(e.getCliStateValue("goalPaused")).isEqualTo("true");
    }

    @Test
    void appendSubgoalAccumulatesInCliState() {
        SessionEntity e = givenSession();

        service.appendSubgoal(SESSION_ID, "first task");
        service.appendSubgoal(SESSION_ID, "second task");

        assertThat(e.getCliStateValue("subgoals")).isEqualTo("first task\nsecond task");
    }

    @Test
    void resetSessionStateClearsCliStateAndSubgoal() {
        SessionEntity e = givenSession();
        e.setCliStateValue("personality", "concise");
        e.setSubgoal("verify cli");

        service.resetSessionState(SESSION_ID);

        assertThat(e.getCliState()).isEmpty();
        assertThat(e.getSubgoal()).isNull();
    }

    @Test
    void missingSessionThrowsIllegalArgumentException() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setTitle(SESSION_ID, "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Session not found");
    }
}
