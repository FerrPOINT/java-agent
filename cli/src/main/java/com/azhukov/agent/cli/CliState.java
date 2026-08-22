package com.azhukov.agent.cli;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Local CLI state storage for settings that don't have dedicated backend endpoints.
 * <p>
 * State is stored in-memory per session and passed via chat request headers/params
 * when sending messages to the backend.
 * <p>
 * P1-4: Used by /verbose, /yolo, /reasoning, /fast, /voice, /busy, /tools, /personality.
 * <p>
 * c16: Registered as a Spring {@code @Component} so it can be injected into
 * command group classes and the registry.
 */
@Component
public class CliState {

    public enum VerboseMode { OFF, NEW, ALL, VERBOSE }
    public enum BusyMode { QUEUE, STEER, INTERRUPT }

    private volatile VerboseMode verboseMode = VerboseMode.OFF;
    private volatile boolean yoloMode = false;
    private volatile String reasoningEffort = "medium";
    private volatile boolean fastMode = false;
    private volatile boolean voiceMode = false;
    private volatile boolean ttsEnabled = false;
    private volatile BusyMode busyMode = BusyMode.QUEUE;
    private volatile String personality = "";
    private volatile String lastUserMessage = "";
    private volatile String queuedPrompt = null;
    private volatile String activeGoal = "";
    private final Map<String, Boolean> toolStates = new ConcurrentHashMap<>();
    private volatile String cdpUrl = "";
    private volatile String currentSessionId = null;
    private volatile boolean debugMode = false;
    private volatile String userProfile = "default";

    public VerboseMode getVerboseMode() { return verboseMode; }
    public void setVerboseMode(VerboseMode verboseMode) { this.verboseMode = verboseMode; }

    public VerboseMode cycleVerboseMode() {
        VerboseMode[] modes = VerboseMode.values();
        int next = (verboseMode.ordinal() + 1) % modes.length;
        this.verboseMode = modes[next];
        return this.verboseMode;
    }

    public boolean isYoloMode() { return yoloMode; }
    public void setYoloMode(boolean yoloMode) { this.yoloMode = yoloMode; }
    public boolean toggleYoloMode() { this.yoloMode = !this.yoloMode; return this.yoloMode; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public boolean isFastMode() { return fastMode; }
    public void setFastMode(boolean fastMode) { this.fastMode = fastMode; }
    public boolean toggleFastMode() { this.fastMode = !this.fastMode; return this.fastMode; }

    public boolean isVoiceMode() { return voiceMode; }
    public void setVoiceMode(boolean voiceMode) { this.voiceMode = voiceMode; }

    public boolean isTtsEnabled() { return ttsEnabled; }
    public void setTtsEnabled(boolean ttsEnabled) { this.ttsEnabled = ttsEnabled; }

    public BusyMode getBusyMode() { return busyMode; }
    public void setBusyMode(BusyMode busyMode) { this.busyMode = busyMode; }

    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }

    public String getLastUserMessage() { return lastUserMessage; }
    public void setLastUserMessage(String lastUserMessage) { this.lastUserMessage = lastUserMessage; }

    public String getQueuedPrompt() { return queuedPrompt; }
    public void setQueuedPrompt(String queuedPrompt) { this.queuedPrompt = queuedPrompt; }

    public String getActiveGoal() { return activeGoal; }
    public void setActiveGoal(String activeGoal) { this.activeGoal = activeGoal; }

    public Map<String, Boolean> getToolStates() { return toolStates; }

    public void setToolEnabled(String toolName, boolean enabled) {
        toolStates.put(toolName, enabled);
    }

    public boolean isToolEnabled(String toolName) {
        return toolStates.getOrDefault(toolName, true);
    }

    public String getCdpUrl() { return cdpUrl; }
    public void setCdpUrl(String cdpUrl) { this.cdpUrl = cdpUrl; }

    /**
     * Get the current session ID (set by /new or /resume commands).
     * @return the current session ID, or null if not set
     */
    public String getCurrentSessionId() { return currentSessionId; }

    /**
     * Set the current session ID (used by /new and /resume to switch sessions).
     * @param currentSessionId the new session ID
     */
    public void setCurrentSessionId(String currentSessionId) { this.currentSessionId = currentSessionId; }

    /** MUST mirror RuntimeSettingsController.VALID_REASONING (backend parity);
     *  synced 2026-08-23 after the CLI set drifted (max/ultra missing). */
    private static final String[] REASONING_LEVELS = {"none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"};

    public String cycleReasoningEffort() {
        int idx = -1;
        for (int i = 0; i < REASONING_LEVELS.length; i++) {
            if (REASONING_LEVELS[i].equalsIgnoreCase(reasoningEffort)) {
                idx = i;
                break;
            }
        }
        int next = (idx + 1) % REASONING_LEVELS.length;
        this.reasoningEffort = REASONING_LEVELS[next];
        return this.reasoningEffort;
    }

    public boolean setReasoningEffortIfValid(String level) {
        for (String valid : REASONING_LEVELS) {
            if (valid.equalsIgnoreCase(level)) {
                this.reasoningEffort = level.toLowerCase();
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the valid reasoning effort levels (defensive copy).
     * <p>
     * c16: Now an instance method so callers go through the injected
     * Spring bean rather than static state. The backing array is a
     * {@code static final} constant (immutable), so no mutable static
     * state remains.
     */
    public String[] getValidReasoningLevels() {
        return REASONING_LEVELS.clone();
    }

    public boolean isDebugMode() { return debugMode; }
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }
    public boolean toggleDebugMode() { this.debugMode = !this.debugMode; return this.debugMode; }

    public String getUserProfile() { return userProfile; }
    public void setUserProfile(String userProfile) { this.userProfile = userProfile; }
}