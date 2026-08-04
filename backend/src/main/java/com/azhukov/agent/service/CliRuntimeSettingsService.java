package com.azhukov.agent.service;

import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for CLI-specific runtime settings.
 * Stores per-session state that the CLI sends via ChatRequest but
 * which should outlive a single turn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CliRuntimeSettingsService {

    private final SessionRepository sessionRepository;
    private final ToolRegistry toolRegistry;

    private SessionEntity getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    @Transactional
    public void setReasoningEffort(UUID sessionId, String level) {
        getSession(sessionId).setCliStateValue("reasoningEffort", level);
    }

    @Transactional
    public void setFastMode(UUID sessionId, boolean fast) {
        getSession(sessionId).setCliStateValue("fastMode", String.valueOf(fast));
    }

    @Transactional
    public void setVoiceMode(UUID sessionId, boolean voice) {
        getSession(sessionId).setCliStateValue("voiceMode", String.valueOf(voice));
    }

    @Transactional
    public void setPersonality(UUID sessionId, String personality) {
        getSession(sessionId).setCliStateValue("personality", personality);
    }

    @Transactional
    public void setSubgoal(UUID sessionId, String subgoal) {
        getSession(sessionId).setSubgoal(subgoal);
    }

    @Transactional
    public void setGoal(UUID sessionId, String goal) {
        getSession(sessionId).setCliStateValue("goal", goal);
    }

    @Transactional
    public void setGoalPaused(UUID sessionId, boolean paused) {
        getSession(sessionId).setCliStateValue("goalPaused", String.valueOf(paused));
    }

    @Transactional
    public void clearGoal(UUID sessionId) {
        SessionEntity e = getSession(sessionId);
        e.removeCliStateValue("goal");
        e.removeCliStateValue("goalPaused");
        e.removeCliStateValue("subgoals");
    }

    @Transactional
    public void appendSubgoal(UUID sessionId, String subgoal) {
        SessionEntity e = getSession(sessionId);
        String existing = e.getCliStateValue("subgoals");
        String updated = (existing == null || existing.isBlank())
            ? subgoal
            : existing + "\n" + subgoal;
        e.setCliStateValue("subgoals", updated);
    }

    @Transactional
    public void clearSubgoals(UUID sessionId) {
        getSession(sessionId).removeCliStateValue("subgoals");
    }

    @Transactional
    public void setTitle(UUID sessionId, String title) {
        getSession(sessionId).setTitle(title);
    }

    @Transactional
    public void setQueuedPrompt(UUID sessionId, String prompt) {
        getSession(sessionId).setCliStateValue("queuedPrompt", prompt);
    }

    @Transactional
    public void setCdpUrl(UUID sessionId, String cdpUrl) {
        getSession(sessionId).setCliStateValue("cdpUrl", cdpUrl);
    }

    @Transactional
    public void enableTool(UUID sessionId, String toolName) {
        SessionEntity e = getSession(sessionId);
        Set<String> disabled = Stream.ofNullable(e.getCliStateValue("disabledTools"))
            .flatMap(s -> Stream.of(s.split(",")))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
        disabled.remove(toolName);
        e.setCliStateValue("disabledTools", disabled.isEmpty() ? null : String.join(",", disabled));
    }

    @Transactional
    public void disableTool(UUID sessionId, String toolName) {
        SessionEntity e = getSession(sessionId);
        Set<String> disabled = Stream.ofNullable(e.getCliStateValue("disabledTools"))
            .flatMap(s -> Stream.of(s.split(",")))
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
        disabled.add(toolName);
        e.setCliStateValue("disabledTools", String.join(",", disabled));
    }

    @Transactional
    public boolean toggleFastMode(UUID sessionId, boolean enabled) {
        setFastMode(sessionId, enabled);
        return enabled;
    }

    @Transactional
    public boolean toggleVoiceMode(UUID sessionId, boolean enabled) {
        setVoiceMode(sessionId, enabled);
        return enabled;
    }

    @Transactional(readOnly = true)
    public List<String> listToolNames() {
        return toolRegistry.getDefinitions().stream()
            .map(def -> def.name())
            .sorted()
            .toList();
    }

    @Transactional
    public void resetSessionState(UUID sessionId) {
        SessionEntity e = getSession(sessionId);
        e.getCliState().clear();
        e.setSubgoal(null);
    }

    @Transactional
    public void resetAllSessions() {
        // Reset all CLI runtime state across all sessions
        sessionRepository.findAll().forEach(e -> {
            e.getCliState().clear();
            e.setSubgoal(null);
        });
    }
}
