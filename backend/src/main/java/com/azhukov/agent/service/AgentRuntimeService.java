package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.*;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.skill.SkillBundleService;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.repository.UsageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRuntimeService {

    /** Single-threaded daemon executor for background jobs (bounded, observable). */
    private final java.util.concurrent.ExecutorService backgroundExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "background-jobs");
            t.setDaemon(true);
            return t;
        });

    private final AgentRuntime agentRuntime;
    private final com.azhukov.agent.persistence.repository.BackgroundJobRepository backgroundJobRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final SessionTitleService sessionTitleService;
    private final MemoryProvider memoryProvider;
    private final MemoryRepository memoryRepository;
    private final WriteApprovalGate writeApprovalGate;
    private final ConversationCompressor conversationCompressor;
    private final UsageTracker usageTracker;
    private final TurnUsageCollector turnUsageCollector;
    private final AgentProperties properties;
    private final SessionEntityMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final DomainDtoMapper domainDtoMapper;
    private final SkillBundleService skillBundleService;
    private final SkillManager skillManager;
    private final McpLifecycleManager mcpLifecycleManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final RuntimeConfigService runtimeConfigService;
    private final TransactionTemplate transactionTemplate;
    private final AgentSessionResolver sessionResolver;
    private final CliStateApplier cliStateApplier;
    private final SessionCompressionHelper sessionCompressionHelper;
    private final com.azhukov.agent.core.context.ContextCompressor contextCompressor;
    private final com.azhukov.agent.core.metadata.ModelMetadataService modelMetadataService;
    private final com.azhukov.agent.core.agent.MidTurnPersistenceCallback midTurnPersistenceCallback;
    private final com.azhukov.agent.core.agent.MemoryNudgeManager memoryNudgeManager;
    private final com.azhukov.agent.core.prompt.DefaultPromptBuilder promptBuilder;

    private static final String UNKNOWN_MODEL = "unknown";

    public ChatResponseDto runDelegate(ChatRequest request) {
        int depth = request.delegationDepth() != null ? request.delegationDepth() : 0;
        Session session = createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "")
            .withMetadata("delegation_depth", String.valueOf(depth));

        // P1-5: Persist user message before turn when mid-turn persistence is active
        if (midTurnPersistenceCallback != null) {
            transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                MessageEntity userMsg = new MessageEntity();
                userMsg.setSessionId(session.id());
                userMsg.setRole("user");
                userMsg.setContent(request.message());
                userMsg.setTurnIndex(0);
                userMsg.setCreatedAt(now);
                messageRepository.save(userMsg);
                return null;
            });
        }

        TurnResult result = agentRuntime.runTurn(session, request.message(), List.of(),
            ModelRequestOptions.empty());
        // P1-5: Only call persistMessages if mid-turn persistence is NOT active
        if (midTurnPersistenceCallback == null) {
            persistMessages(session.id(), result.messages());
        }

        return buildResponse(session, result, false);
    }

    public ChatResponseDto runTurn(ChatRequest request) {
        ChatRequest applied = applyCliState(request);
        var resolved = sessionResolver.resolveOrCreate(
            applied.sessionId(), AgentProperties.DEFAULT_USER_ID, properties.getModel().getModelName());
        boolean isNew = resolved.isNew();
        Session session = resolved.session();

        // P1-5: When mid-turn persistence is active, persist the user message before
        // the turn starts. The DefaultAgentRuntime will persist assistant messages
        // and tool results mid-turn via the MidTurnPersistenceCallback. This avoids
        // duplicate writes at end-of-turn.
        if (midTurnPersistenceCallback != null) {
            transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                MessageEntity userMsg = new MessageEntity();
                userMsg.setSessionId(session.id());
                userMsg.setRole("user");
                userMsg.setContent(applied.message());
                userMsg.setTurnIndex(0);
                userMsg.setCreatedAt(now);
                messageRepository.save(userMsg);
                return null;
            });
        }

        ModelRequestOptions options = toModelOptions(applied, session);
        TurnResult result = agentRuntime.runTurn(session, applied.message(), List.of(), options);
        // P1-5: Only call persistMessages if mid-turn persistence is NOT active
        // (mid-turn persistence already saved all new messages during the turn)
        if (midTurnPersistenceCallback == null) {
            persistMessages(session.id(), result.messages());
        }
        sessionTitleService.maybeUpdateTitle(session.id(), result.messages(), isNew);

        // Record token usage from the turn
        int[] usage = turnUsageCollector.getAndClear();
        if (usage != null && usage.length == 2) {
            usageTracker.recordTurn(session.id(), session.userId(),
                session.modelName() != null ? session.modelName() : UNKNOWN_MODEL,
                usage[0], usage[1]);
        }

        return buildResponse(session, result, false);
    }

    private ChatResponseDto buildResponse(Session session, TurnResult result, boolean memoryUpdated) {
        String modelUsed = resolveModelUsed(session);
        UsageDto usage = usageTracker.getSessionUsage(session.id());
        int contextLength = properties != null && properties.getContext() != null
            ? properties.getContext().getMaxTokens()
            : 0;
        return new ChatResponseDto(
            session.id(),
            result.finalText(),
            null,
            result.completed(),
            memoryUpdated,
            modelUsed,
            usage != null ? usage.tokenEstimate() : null,
            contextLength
        );
    }

    private String resolveModelUsed(Session session) {
        if (session.modelName() != null && !session.modelName().isBlank()) {
            return session.modelName();
        }
        String override = runtimeConfigService.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (properties != null && properties.getModel() != null
            && properties.getModel().getModelName() != null
            && !properties.getModel().getModelName().isBlank()) {
            return properties.getModel().getModelName();
        }
        return UNKNOWN_MODEL;
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryDto> listSessions() {
        return sessionRepository.findAllByUserId(AgentProperties.DEFAULT_USER_ID, PageRequest.of(0, 50)).stream()
            .map(sessionMapper::toDomain)
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public ContextInfoDto getContext(UUID sessionId) {
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int messageCount = messages.size();
        int tokenEstimate = messages.stream()
            .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
            .sum() / 4;
        List<String> toolsUsed = messages.stream()
            .map(MessageEntity::getToolCallName)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .collect(Collectors.toList());

        // Populate goal-related fields from session cliState
        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        String goal = null;
        Boolean goalPaused = null;
        String subgoals = null;
        if (session != null) {
            goal = session.getCliStateValue("goal");
            String pausedStr = session.getCliStateValue("goalPaused");
            goalPaused = pausedStr != null ? Boolean.valueOf(pausedStr) : null;
            subgoals = session.getCliStateValue("subgoals");
        }
        return new ContextInfoDto(sessionId, messageCount, tokenEstimate, toolsUsed, goal, goalPaused, subgoals);
    }

    @Transactional
    public void resetSession(UUID sessionId) {
        messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        // Hermes parity / leak fix (seam audit 2026-08-21): /reset must also drop
        // runtime per-session state, or stale counters leak into the "fresh" session:
        // - MemoryNudgeManager.clearSession (turnsSinceMemory, itersSinceSkill) and
        //   DefaultAgentRuntime.cleanupSession (locks, guardrail state) — were NEVER
        //   called (memory leak + nudge counters survived a reset, so a review could
        //   fire on turn 1 of the "new" session)
        // - DefaultPromptBuilder.invalidateMemoryPrefix — memory prefix is frozen
        //   per session (Hermes snapshot invariant); after /reset the next turn must
        //   re-read memory, not replay the old snapshot
        try {
            if (agentRuntime instanceof com.azhukov.agent.core.agent.DefaultAgentRuntime dar) {
                dar.cleanupSession(sessionId);
            }
        } catch (Exception e) {
            log.debug("runtime cleanup on reset failed for {}: {}", sessionId, e.getMessage());
        }
        try {
            memoryNudgeManager.clearSession(sessionId);
        } catch (Exception e) {
            log.debug("nudge counter cleanup on reset failed for {}: {}", sessionId, e.getMessage());
        }
        try {
            promptBuilder.invalidateMemoryPrefix(String.valueOf(sessionId));
        } catch (Exception e) {
            log.debug("memory prefix invalidation on reset failed for {}: {}", sessionId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public UsageDto getUsage(UUID sessionId) {
        return usageTracker.getSessionUsage(sessionId);
    }

    @Transactional(readOnly = true)
    public CreditsDto getCreditsSummary() {
        var credits = usageTracker.getCreditsSummary(null);
        return new CreditsDto(credits.totalCost(), credits.totalTokens(), credits.totalMessages());
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryDto> listSessionsByUserId(String userId) {
        return sessionRepository.findAllByUserId(userId, PageRequest.of(0, 50)).stream()
            .map(sessionMapper::toDomain)
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();
    }

    @Transactional
    public void compressSession(UUID sessionId, String focus) {
        sessionCompressionHelper.compressSessionInternal(sessionId, focus, null);
    }

    @Transactional
    public void compressSession(UUID sessionId, String focusTopic, Integer keepLastN) {
        sessionCompressionHelper.compressSessionInternal(sessionId, focusTopic, keepLastN);
    }

    @Transactional
    public int undoTurns(UUID sessionId, int turns) {
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) return 0;
        List<Integer> turnIndices = messages.stream()
            .map(MessageEntity::getTurnIndex)
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
        int turnsToDelete = Math.min(turns, turnIndices.size());
        if (turnsToDelete == 0) return 0;
        int cutoffTurnIndex = turnIndices.get(turnsToDelete - 1);
        List<MessageEntity> toDelete = messages.stream()
            .filter(m -> m.getTurnIndex() != null && m.getTurnIndex() >= cutoffTurnIndex)
            .toList();
        messageRepository.deleteAll(toDelete);
        return toDelete.size();
    }

    @Transactional(readOnly = true)
    public List<ActiveAgentDto> listActiveAgents() {
        return sessionRepository.findAllByUserId(AgentProperties.DEFAULT_USER_ID, PageRequest.of(0, 50)).stream()
            .map(e -> new ActiveAgentDto(
                e.getId().toString(),
                "active",
                e.getCreatedAt() != null ? e.getCreatedAt().toEpochMilli() : 0L,
                e.getTitle() != null ? e.getTitle() : ""
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public InsightsDto getInsights() {
        return usageTracker.getInsights(null);
    }

    @Transactional
    /**
     * Restart (Hermes parity: gateway/slash_commands.py _handle_restart_command —
     * drain active work and reload runtime state; conversation history is
     * NEVER wiped). The old implementation deleted every message of the
     * default user — destructive and divergent.
     */
    public void restart() {
        log.info("Restarting agent — draining active turns and reloading runtime state (history preserved)");
        skillManager.reload();
        mcpLifecycleManager.closeAll();
        mcpLifecycleManager.connectConfiguredServers();
        runtimeConfigService.clearModelOverride();
        log.info("Agent restart complete — history preserved");
    }

    public void reloadMcp() {
        log.info("Reloading MCP connections");
        mcpLifecycleManager.closeAll();
        mcpLifecycleManager.connectConfiguredServers();
        log.info("MCP reload complete");
    }

    public void reloadSkills() {
        log.info("Reloading skills");
        skillManager.reload();
        log.info("Skills reload complete");
    }

    @Transactional(readOnly = true)
    public List<String> listBundles() {
        return skillBundleService.listBundlesInfo().stream()
            .map(SkillBundleService.Bundle::name)
            .toList();
    }

    @Transactional
    public void switchModel(UUID sessionId, String model, String provider) {
        SessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        session.setModelName(model);
        if (provider != null && !provider.isBlank()) {
            session.setModelProvider(provider);
        }
        session.setUpdatedAt(Instant.now());
        sessionRepository.save(session);

        // P2-51: Recalculate compression threshold for the new model's context window.
        // Different models have different context window sizes, so the threshold at which
        // compression kicks in must be updated when the model switches.
        if (modelMetadataService != null && contextCompressor != null && model != null && !model.isBlank()) {
            int newContextWindowSize = modelMetadataService.detectContextLength(model);
            // Hermes parity: pass model name + per-model threshold overrides so the
            // compression policy can apply per-model and small-context floor adjustments.
            if (contextCompressor instanceof DefaultContextCompressor dcc) {
                dcc.recalculateThreshold(newContextWindowSize, model,
                    properties.getContext().getModelThresholds());
            } else {
                contextCompressor.recalculateThreshold(newContextWindowSize);
            }
            log.info("Model switched for session {}: model={}, contextWindow={}, compression threshold recalculated",
                sessionId, model, newContextWindowSize);
        }
    }

    @Transactional
    public void installBundle(String bundleName) {
        skillBundleService.install(bundleName);
    }

    public void uninstallBundle(String bundleName) {
        skillBundleService.uninstall(bundleName);
    }

    @Transactional
    public SessionSummaryDto branchSession(UUID sessionId, String name) {
        // Fork a session: load messages, create new session with copied messages
        SessionEntity source = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        SessionEntity branch = sessionMapper.toEntity(
            new Session(null, source.getUserId(), name != null ? name : "Branch of " + source.getTitle(),
                source.getModelProvider(), source.getModelName(), null, java.util.Map.of(), null)
        );
        branch.setCreatedAt(Instant.now());
        branch.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(branch);
        // Copy messages
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<MessageEntity> copies = new java.util.ArrayList<>(messages.size());
        for (MessageEntity m : messages) {
            MessageEntity copy = messageMapper.toEntity(messageMapper.toDomain(m));
            copy.setSessionId(saved.getId());
            copy.setCreatedAt(m.getCreatedAt());
            copies.add(copy);
        }
        messageRepository.saveAll(copies);
        return domainDtoMapper.toSessionSummaryDto(sessionMapper.toDomain(saved));
    }

    public String runBackground(String prompt, String sessionId) {
        return runBackground(prompt, sessionId, false);
    }

    /**
     * Heartbeat turn (Hermes hermes_cli/heartbeat.py): fires the recurring
     * instruction as a NORMAL USER TURN in the SAME session — session-scoped,
     * unlike {@link #runBackground} which always creates a fresh session.
     */
    public String runHeartbeatTurn(UUID sessionId, String prompt) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        Session session;
        if (entity != null) {
            session = sessionMapper.toDomain(entity);
        } else {
            session = createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "");
        }
        TurnResult result = agentRuntime.runTurn(session, prompt);
        persistMessages(session.id(), result.messages());
        return result.messages().stream()
            .filter(m -> m.role() != null && m.role().name().equalsIgnoreCase("assistant"))
            .reduce((a, b) -> b)
            .map(m -> m.content() == null ? "" : m.content())
            .orElse("");
    }

    /**
     * Background turn (cron / scripted tasks).
     *
     * @param skipBackgroundReview Hermes parity (cron/scheduler.py:5459): cron sessions
     *                             pass skip_background_review=True — review forks cost
     *                             ~30K tokens/event and cron has no human-in-the-loop
     *                             benefit from a memory/skill review. Before this flag
     *                             every cron tick could silently spend an extra LLM call.
     */
    /**
     * Background job with persisted status + result (Hermes parity:
     * run_in_background exposes job status/result for polling).
     */
    @org.springframework.transaction.annotation.Transactional
    public java.util.UUID submitBackgroundJob(String prompt, String sessionId, boolean skipBackgroundReview) {
        com.azhukov.agent.persistence.entity.BackgroundJobEntity job =
            new com.azhukov.agent.persistence.entity.BackgroundJobEntity();
        job.setPrompt(prompt);
        job.setStatus("PENDING");
        job.setSessionId(sessionId != null && !sessionId.isBlank() ? java.util.UUID.fromString(sessionId) : null);
        job = backgroundJobRepository.save(job);
        java.util.UUID jobId = job.getId();
        backgroundExecutor.submit(() -> {
            try {
                backgroundJobRepository.updateStatus(jobId, "RUNNING");
                String result = runBackground(prompt, sessionId, skipBackgroundReview);
                backgroundJobRepository.finish(jobId, "DONE", result, java.time.Instant.now());
            } catch (Exception e) {
                log.error("Background job {} failed", jobId, e);
                backgroundJobRepository.finish(jobId, "FAILED", e.getMessage() == null ? e.toString() : e.getMessage(), java.time.Instant.now());
            }
        });
        return jobId;
    }

    public String runBackground(String prompt, String sessionId, boolean skipBackgroundReview) {
        return runBackground(prompt, sessionId, skipBackgroundReview, java.util.Map.of());
    }

    /**
     * Background task with isolated-session runtime metadata.
     * Metadata is consumed by the runtime/tool layer, not rendered into the model prompt.
     */
    public String runBackground(String prompt, String sessionId, boolean skipBackgroundReview,
                                java.util.Map<String, String> runtimeMetadata) {
        // Background task — just run a turn in a new session
        Session baseSession = createSession(AgentProperties.DEFAULT_USER_ID, "openai-compatible", "");
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        if (runtimeMetadata != null) {
            runtimeMetadata.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    metadata.put(key, value);
                }
            });
        }
        if (skipBackgroundReview) {
            metadata.put("skip_background_review", "true");
        }
        final Session session = metadata.isEmpty() ? baseSession
            : new Session(baseSession.id(), baseSession.userId(), baseSession.title(),
                baseSession.modelProvider(), baseSession.modelName(), baseSession.systemPrompt(),
                java.util.Map.copyOf(metadata), baseSession.subgoal());
        // P1-5: Persist user message before turn when mid-turn persistence is active
        if (midTurnPersistenceCallback != null) {
            transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                MessageEntity userMsg = new MessageEntity();
                userMsg.setSessionId(session.id());
                userMsg.setRole("user");
                userMsg.setContent(prompt);
                userMsg.setTurnIndex(0);
                userMsg.setCreatedAt(now);
                messageRepository.save(userMsg);
                return null;
            });
        }
        TurnResult result = agentRuntime.runTurn(session, prompt);
        // P1-5: Only call persistMessages if mid-turn persistence is NOT active
        if (midTurnPersistenceCallback == null) {
            persistMessages(session.id(), result.messages());
        }
        return session.id().toString();
    }

    // ── Memory management methods (Stage 6.8) ──

    @Transactional(readOnly = true)
    public List<PendingMemoryDto> listPendingMemory(String userId) {
        return writeApprovalGate.listPending(userId).stream()
            .map(e -> new PendingMemoryDto(e.getId(), e.getUserId(), e.getAction(), e.getTarget(),
                e.getContent(), e.getOldText(), e.getSummary(), e.getOrigin(), e.getStatus(),
                e.getCreatedAt(), e.getResolvedAt()))
            .toList();
    }

    @Transactional
    public boolean approvePendingMemory(ApproveMemoryRequest request) {
        return writeApprovalGate.approve(request.userId(), request.id());
    }

    @Transactional
    public boolean rejectPendingMemory(RejectMemoryRequest request) {
        return writeApprovalGate.reject(request.userId(), request.id());
    }

    @Transactional
    public void setMemoryApproval(boolean enabled) {
        writeApprovalGate.setApproval(enabled);
    }

    @Transactional(readOnly = true)
    public boolean isMemoryApprovalEnabled() {
        return writeApprovalGate.isEnabled();
    }

    @Transactional(readOnly = true)
    public List<MemoryDto> listAllMemory(String userId) {
        // H13: Use bounded query to avoid loading an unbounded result set.
        return memoryRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1000)).stream()
            .map(e -> new MemoryDto(e.getId(), e.getUserId(), e.getCategory(), e.getFact(),
                e.getTarget(), e.getCreatedAt()))
            .toList();
    }

    @Transactional
    public void deleteMemory(String userId, UUID entryId) {
        memoryRepository.findById(entryId).ifPresent(e -> {
            if (e.getUserId().equals(userId)) {
                memoryRepository.delete(e);
            }
        });
    }

    public Session createSession(String userId, String provider, String modelName) {
        return sessionResolver.createSession(userId, provider, modelName);
    }

    private void persistMessages(UUID sessionId, List<Message> messages) {
        transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            for (Message m : messages) {
                MessageEntity e = messageMapper.toEntity(m);
                e.setSessionId(sessionId);
                e.setCreatedAt(now);
                if (m.toolCalls() != null && !m.toolCalls().isEmpty() && m.toolCall() == null) {
                    e.setToolCallName(m.toolCalls().get(0).name());
                    e.setToolCallArguments(m.toolCalls().get(0).arguments());
                }
                messageRepository.save(e);
            }
            return null;
        });
    }

    /**
     * Apply CLI runtime settings from the request to the session.
     * Wraps the SessionEntity load and cliState initialization in a
     * read-only transaction to avoid LazyInitializationException when
     * the lazy cliState ElementCollection is accessed outside a Hibernate session.
     */
    private ChatRequest applyCliState(ChatRequest request) {
        if (request.sessionId() == null) {
            return request;
        }
        SessionEntity session = transactionTemplate.execute(status -> {
            SessionEntity e = sessionRepository.findById(request.sessionId()).orElse(null);
            if (e != null) {
                org.hibernate.Hibernate.initialize(e.getCliState());
            }
            return e;
        });
        return cliStateApplier.applyCliState(request, session);
    }

    private ModelRequestOptions toModelOptions(ChatRequest request, Session session) {
        Boolean fastMode = request.fastMode();
        String reasoningEffort = request.reasoningEffort();
        if (fastMode == null) {
            fastMode = Boolean.parseBoolean(session.metadata().getOrDefault("fastMode", "false"));
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = session.metadata().get("reasoningEffort");
        }
        Integer maxTokens;
        try {
            maxTokens = Integer.parseInt(session.metadata().getOrDefault("maxTokens", "0"));
        } catch (NumberFormatException e) {
            maxTokens = 0;
        }
        return new ModelRequestOptions(
            request.model() != null && !request.model().isBlank() ? request.model() : null,
            reasoningEffort, fastMode, request.voiceMode(),
            request.personality(), request.subgoal(), maxTokens);
    }
}
