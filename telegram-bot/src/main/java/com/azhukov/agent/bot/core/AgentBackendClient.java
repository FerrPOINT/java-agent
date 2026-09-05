package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Facade over the per-domain backend API clients.
 *
 * <p>Historically a single 1474-LOC REST client, the logic is now split into
 * domain-specific delegates ({@link SessionApiClient}, {@link MessageApiClient},
 * {@link ToolApiClient}, {@link SkillApiClient}, {@link MemoryApiClient},
 * {@link CronApiClient}, {@link ApprovalApiClient}, {@link ModelApiClient}).
 * This class preserves the original public API by delegating every call to the
 * appropriate client, so existing callers (commands, orchestrators, services)
 * require no changes.
 *
 * <p>The {@link ChatResult} record is defined here and shared by all delegates.
 */
@Service
@Slf4j
public class AgentBackendClient {

    private final SessionApiClient sessions;
    private final MessageApiClient messages;
    private final ToolApiClient tools;
    private final SkillApiClient skills;
    private final MemoryApiClient memory;
    private final CronApiClient cron;
    private final ApprovalApiClient approvals;
    private final ModelApiClient model;

    public AgentBackendClient(SessionApiClient sessions,
                              MessageApiClient messages,
                              ToolApiClient tools,
                              SkillApiClient skills,
                              MemoryApiClient memory,
                              CronApiClient cron,
                              ApprovalApiClient approvals,
                              ModelApiClient model) {
        this.sessions = sessions;
        this.messages = messages;
        this.tools = tools;
        this.skills = skills;
        this.memory = memory;
        this.cron = cron;
        this.approvals = approvals;
        this.model = model;
    }

    /**
     * Result of a chat call, including the response content and runtime metadata
     * for the footer (model, context usage, working directory).
     */
    public record ChatResult(
        String content,
        String modelUsed,
        Integer contextTokens,
        Integer contextLength,
        boolean streamFinalized,
        boolean memoryUpdated,
        java.util.UUID backendSessionId,
        String lastReasoning
    ) {
        public ChatResult(String content) {
            this(content, null, null, null, false, false, null, null);
        }

        public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength) {
            this(content, modelUsed, contextTokens, contextLength, false, false, null, null);
        }

        public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength, boolean streamFinalized) {
            this(content, modelUsed, contextTokens, contextLength, streamFinalized, false, null, null);
        }

        public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength, boolean streamFinalized, boolean memoryUpdated) {
            this(content, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated, null, null);
        }

        public ChatResult(String content, String modelUsed, Integer contextTokens, Integer contextLength, boolean streamFinalized, boolean memoryUpdated, java.util.UUID backendSessionId) {
            this(content, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated, backendSessionId, null);
        }
    }

    // ------------------------------------------------------------------
    // Messaging (MessageApiClient)
    // ------------------------------------------------------------------

    public ChatResult chat(String message, String sessionId, BotSessionEntity runtime) {
        return messages.chat(message, sessionId, runtime);
    }

    public ChatResult chat(String message, String sessionId) {
        return messages.chat(message, sessionId);
    }

    public ChatResult chatStream(String message,
                                 String sessionId,
                                 BotSessionEntity runtime,
                                 Consumer<String> tokenConsumer,
                                 Consumer<String> toolCallConsumer,
                                 java.util.function.BiConsumer<String, String> toolResultConsumer,
                                 Consumer<String> retryConsumer,
                                 Consumer<ChatResult> onComplete,
                                 Consumer<Throwable> onError) {
        return messages.chatStream(message, sessionId, runtime, tokenConsumer, toolCallConsumer,
            toolResultConsumer, retryConsumer, null, onComplete, onError);
    }

    public ChatResult chatStream(String message,
                                 String sessionId,
                                 BotSessionEntity runtime,
                                 Consumer<String> tokenConsumer,
                                 Consumer<String> toolCallConsumer,
                                 java.util.function.BiConsumer<String, String> toolResultConsumer,
                                 Consumer<String> retryConsumer,
                                 Consumer<String> reviewConsumer,
                                 Consumer<ChatResult> onComplete,
                                 Consumer<Throwable> onError) {
        return messages.chatStream(message, sessionId, runtime, tokenConsumer, toolCallConsumer,
            toolResultConsumer, retryConsumer, reviewConsumer, onComplete, onError);
    }

    public ChatResult chatStream(String message,
                                 String sessionId,
                                 Consumer<String> tokenConsumer,
                                 Consumer<String> toolCallConsumer,
                                 java.util.function.BiConsumer<String, String> toolResultConsumer,
                                 Consumer<ChatResult> onComplete,
                                 Consumer<Throwable> onError) {
        return messages.chatStream(message, sessionId, tokenConsumer, toolCallConsumer,
            toolResultConsumer, onComplete, onError);
    }

    public ChatResult chatStreamReview(String message,
                                       String sessionId,
                                       Consumer<String> tokenConsumer,
                                       Consumer<String> toolCallConsumer,
                                       java.util.function.BiConsumer<String, String> toolResultConsumer,
                                       Consumer<String> retryConsumer,
                                       Consumer<String> reviewConsumer,
                                       Consumer<ChatResult> onComplete,
                                       Consumer<Throwable> onError) {
        return messages.chatStream(message, sessionId, null, tokenConsumer, toolCallConsumer,
            toolResultConsumer, retryConsumer, reviewConsumer, onComplete, onError);
    }

    public byte[] tts(String text, String voice) {
        return messages.tts(text, voice);
    }

    public String transcribe(byte[] audioBytes) {
        return messages.transcribe(audioBytes);
    }

    // ------------------------------------------------------------------
    // Sessions (SessionApiClient)
    // ------------------------------------------------------------------

    public boolean resetSession(String sessionId) {
        return sessions.resetSession(sessionId);
    }

    public JsonNode getContext(String sessionId) {
        return sessions.getContext(sessionId);
    }

    public JsonNode getUsage(String sessionId) {
        return sessions.getUsage(sessionId);
    }

    public JsonNode listSessionsByUser(String userId) {
        return sessions.listSessionsByUser(userId);
    }

    public String compressSession(String sessionId, String focus) {
        return sessions.compressSession(sessionId, focus);
    }

    public String undoTurns(String sessionId, int turns) {
        return sessions.undoTurns(sessionId, turns);
    }

    public String compressSessionPartial(String sessionId, int keepLastN) {
        return sessions.compressSessionPartial(sessionId, keepLastN);
    }

    public String listCheckpoints() {
        return sessions.listCheckpoints();
    }

    public String restoreCheckpoint(String checkpointId) {
        return sessions.restoreCheckpoint(checkpointId);
    }

    public String createCheckpoint(String description) {
        return sessions.createCheckpoint(description);
    }

    public String branchSession(String sessionId, String name) {
        return sessions.branchSession(sessionId, name);
    }

    public boolean steer(String sessionId, String text) {
        return sessions.steer(sessionId, text);
    }

    public boolean stop(String sessionId) {
        return sessions.stop(sessionId);
    }

    /** Live transcript (backend /api/v2/sessions/{id}/messages), newest last. */
    public com.fasterxml.jackson.databind.JsonNode getMessages(String sessionId, int limit) {
        return sessions.getMessages(sessionId, limit);
    }

    public boolean clearGoal(String sessionId) {
        return sessions.clearGoal(sessionId);
    }

    public boolean setGoal(String sessionId, String goal) {
        return sessions.setGoal(sessionId, goal);
    }

    public boolean pauseGoal(String sessionId) {
        return sessions.pauseGoal(sessionId);
    }

    public boolean resumeGoal(String sessionId) {
        return sessions.resumeGoal(sessionId);
    }

    public boolean appendSubgoal(String sessionId, String subgoal) {
        return sessions.appendSubgoal(sessionId, subgoal);
    }

    public boolean clearSubgoals(String sessionId) {
        return sessions.clearSubgoals(sessionId);
    }

    // ------------------------------------------------------------------
    // Tools / agents / kanban (ToolApiClient)
    // ------------------------------------------------------------------

    public String reloadMcp() {
        return tools.reloadMcp();
    }

    public JsonNode listActiveAgents() {
        return tools.listActiveAgents();
    }

    public JsonNode getKanban() {
        return tools.getKanban();
    }

    public JsonNode addKanbanTask(String text) {
        return tools.addKanbanTask(text);
    }

    public boolean doneKanbanTask(String id) {
        return tools.doneKanbanTask(id);
    }

    public boolean clearKanban() {
        return tools.clearKanban();
    }

    // ------------------------------------------------------------------
    // Skills / bundles (SkillApiClient)
    // ------------------------------------------------------------------

    public JsonNode getSkills() {
        return skills.getSkills();
    }

    public String reloadSkills() {
        return skills.reloadSkills();
    }

    /** rev-105: skill slash-command invocation (Hermes gateway/run.py:18055+). */
    public String invokeSkill(String command, String userInstruction, String sessionId) {
        return skills.invokeSkill(command, userInstruction, sessionId);
    }

    public String reloadAll() {
        return skills.reloadAll();
    }

    public JsonNode listBundles() {
        return skills.listBundles();
    }

    public String installBundle(String bundleName) {
        return skills.installBundle(bundleName);
    }

    public String uninstallBundle(String bundleName) {
        return skills.uninstallBundle(bundleName);
    }

    // ------------------------------------------------------------------
    // Memory (MemoryApiClient)
    // ------------------------------------------------------------------

    public JsonNode getMemory() {
        return memory.getMemory();
    }

    public JsonNode listAllMemory(String userId) {
        return memory.listAllMemory(userId);
    }

    public boolean storeMemory(String userId, String text) {
        return memory.storeMemory(userId, text);
    }

    public boolean deleteMemory(String userId, String entryId) {
        return memory.deleteMemory(userId, entryId);
    }

    public JsonNode listPendingMemory(String userId) {
        return memory.listPendingMemory(userId);
    }

    public boolean approvePendingMemory(String userId, String id) {
        return memory.approvePendingMemory(userId, id);
    }

    public boolean rejectPendingMemory(String userId, String id) {
        return memory.rejectPendingMemory(userId, id);
    }

    public void setMemoryApproval(boolean enabled) {
        memory.setMemoryApproval(enabled);
    }

    public boolean isMemoryApprovalEnabled() {
        return memory.isMemoryApprovalEnabled();
    }

    // ------------------------------------------------------------------
    // Cron (CronApiClient)
    // ------------------------------------------------------------------

    public JsonNode listCronJobs() {
        return cron.listCronJobs();
    }

    public JsonNode listSuggestions() {
        return cron.listSuggestions();
    }

    public JsonNode suggestionPost(String path) {
        return cron.suggestionPost(path);
    }

    public JsonNode suggestionGet(String path) {
        return cron.suggestionGet(path);
    }

    public JsonNode suggestionPostJson(String path, Object body) {
        return cron.suggestionPostJson(path, body);
    }

    public boolean deleteCronJob(String id) {
        return cron.deleteCronJob(id);
    }

    public boolean pauseCronJob(String id) {
        return cron.pauseCronJob(id);
    }

    public boolean resumeCronJob(String id) {
        return cron.resumeCronJob(id);
    }

    // ------------------------------------------------------------------
    // Approvals (ApprovalApiClient)
    // ------------------------------------------------------------------

    public String approve(boolean all, String scope) {
        return approvals.approve(all, scope);
    }

    public String deny(boolean all) {
        return approvals.deny(all);
    }

    public String resolveApproval(String sessionKey, String choice) {
        return approvals.resolveApproval(sessionKey, choice);
    }

    public String resolveSlashConfirm(String sessionKey, String confirmId, String choice) {
        return approvals.resolveSlashConfirm(sessionKey, confirmId, choice);
    }

    // ------------------------------------------------------------------
    // Model / runtime / diagnostics (ModelApiClient)
    // ------------------------------------------------------------------

    public boolean health() {
        return model.health();
    }

    public String restart() {
        return model.restart();
    }

    public JsonNode getInsights() {
        return model.getInsights();
    }

    public String runBackground(String prompt, String sessionId) {
        return model.runBackground(prompt, sessionId);
    }

    public String getBaseUrl() {
        return model.getBaseUrl();
    }
}