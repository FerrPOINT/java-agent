package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.*;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelRequestOptions;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final AgentRuntime agentRuntime;
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

    private static final String UNKNOWN_MODEL = "unknown";

    @Transactional
    public ChatResponseDto runDelegate(ChatRequest request) {
        int depth = request.delegationDepth() != null ? request.delegationDepth() : 0;
        Session session = createSession("user-1", "openai-compatible", "")
            .withMetadata("delegation_depth", String.valueOf(depth));

        TurnResult result = agentRuntime.runTurn(session, request.message(), List.of(),
            ModelRequestOptions.empty());
        persistMessages(session.id(), result.messages());

        return buildResponse(session, result, false);
    }

    @Transactional
    public ChatResponseDto runTurn(ChatRequest request) {
        ChatRequest applied = applyCliState(request);
        boolean isNew;
        Session session;
        if (applied.sessionId() == null) {
            isNew = true;
            session = createSession("user-1", "openai-compatible", properties.getModel().getModelName());
        } else {
            try {
                isNew = false;
                session = loadSession(applied.sessionId());
            } catch (IllegalArgumentException e) {
                log.warn("Session {} not found in backend (sync path), creating new session", applied.sessionId());
                isNew = true;
                session = createSession("user-1", "openai-compatible", properties.getModel().getModelName());
            }
        }

        ModelRequestOptions options = toModelOptions(applied, session);
        TurnResult result = agentRuntime.runTurn(session, applied.message(), List.of(), options);
        persistMessages(session.id(), result.messages());
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
        return sessionRepository.findAllByUserId("user-1").stream()
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
        return new ContextInfoDto(sessionId, messageCount, tokenEstimate, toolsUsed);
    }

    @Transactional
    public void resetSession(UUID sessionId) {
        messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    @Transactional(readOnly = true)
    public UsageDto getUsage(UUID sessionId) {
        return usageTracker.getSessionUsage(sessionId);
    }

    @Transactional(readOnly = true)
    public CreditsDto getCreditsSummary() {
        var insights = usageTracker.getInsights(null);
        double totalCost = usageTracker.getTotalCost(null);
        return new CreditsDto(totalCost, insights.totalTokens(), insights.totalMessages());
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryDto> listSessionsByUserId(String userId) {
        return sessionRepository.findAllByUserId(userId).stream()
            .map(sessionMapper::toDomain)
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();
    }

    @Transactional
    public void compressSession(UUID sessionId, String focus) {
        compressSession(sessionId, focus, null);
    }

    @Transactional
    public void compressSession(UUID sessionId, String focusTopic, Integer keepLastN) {
        List<MessageEntity> messageEntities = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messageEntities.size() <= 4) return;

        // Convert to core Message objects
        List<Message> messages = messageEntities.stream()
            .map(messageMapper::toDomain)
            .toList();

        // Use ConversationCompressor for LLM-based compression
        List<Message> compressed;
        if (keepLastN != null && keepLastN > 0) {
            compressed = conversationCompressor.compressPartial(messages, keepLastN);
        } else {
            compressed = conversationCompressor.compress(messages, focusTopic);
        }

        // Delete all old messages and persist compressed versions
        messageRepository.deleteAll(messageEntities);
        Instant now = Instant.now();
        for (Message m : compressed) {
            MessageEntity e = messageMapper.toEntity(m);
            e.setSessionId(sessionId);
            e.setCreatedAt(now);
            messageRepository.save(e);
        }
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
        return sessionRepository.findAllByUserId("user-1").stream()
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
    public void restart() {
        log.info("Restarting agent — clearing all session messages for user-1");
        for (SessionEntity session : sessionRepository.findAllByUserId("user-1")) {
            messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()));
        }
        log.info("Agent restart complete — all session messages cleared");
    }

    @Transactional
    public void reloadMcp() {
        log.info("Reloading MCP connections");
        mcpLifecycleManager.closeAll();
        mcpLifecycleManager.connectConfiguredServers();
        log.info("MCP reload complete");
    }

    @Transactional
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
        for (MessageEntity m : messages) {
            MessageEntity copy = messageMapper.toEntity(messageMapper.toDomain(m));
            copy.setSessionId(saved.getId());
            copy.setCreatedAt(m.getCreatedAt());
            messageRepository.save(copy);
        }
        return domainDtoMapper.toSessionSummaryDto(sessionMapper.toDomain(saved));
    }

    @Transactional
    public String runBackground(String prompt, String sessionId) {
        // Background task — just run a turn in a new session
        Session session = createSession("user-1", "openai-compatible", "");
        TurnResult result = agentRuntime.runTurn(session, prompt);
        persistMessages(session.id(), result.messages());
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
        return memoryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
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

    @Transactional
    public Session createSession(String userId, String provider, String modelName) {
        SessionEntity e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(e);
        return sessionMapper.toDomain(saved);
    }

    private Session loadSession(UUID id) {
        SessionEntity e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        Session session = sessionMapper.toDomain(e);
        // Hydrate CLI state metadata from session_cli_state into session metadata for runtime use
        if (e.getCliState() != null && !e.getCliState().isEmpty()) {
            for (var entry : e.getCliState().entrySet()) {
                session = session.withMetadata(entry.getKey(), entry.getValue());
            }
        }
        if (e.getSubgoal() != null && !e.getSubgoal().isBlank()) {
            session = session.withMetadata("subgoal", e.getSubgoal());
        }
        return session;
    }

    private void persistMessages(UUID sessionId, List<Message> messages) {
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
    }

    /**
     * Apply CLI runtime settings from the request to the session.
     */
    private ChatRequest applyCliState(ChatRequest request) {
        if (request.sessionId() == null) {
            return request;
        }
        SessionEntity session = sessionRepository.findById(request.sessionId()).orElse(null);
        if (session == null) {
            return request;
        }
        String reasoningEffort = request.reasoningEffort() != null ? request.reasoningEffort() : session.getCliStateValue("reasoningEffort");
        String personality = request.personality() != null ? request.personality() : session.getCliStateValue("personality");
        String queuedPrompt = request.queuedPrompt() != null ? request.queuedPrompt() : session.getCliStateValue("queuedPrompt");
        String subgoal = request.subgoal() != null ? request.subgoal() : session.getSubgoal();
        String goal = request.goal() != null ? request.goal() : session.getCliStateValue("goal");
        if (goal == null || "true".equals(session.getCliStateValue("goalPaused"))) {
            goal = null;
        }
        String subgoals = session.getCliStateValue("subgoals");

        String finalMessage = buildMergedMessage(request.message(), queuedPrompt, goal, subgoals, subgoal);
        return new ChatRequest(
            request.sessionId(),
            finalMessage,
            request.delegationDepth(),
            request.timeoutMs(),
            reasoningEffort,
            request.fastMode(),
            request.voiceMode(),
            personality,
            request.enabledTools(),
            request.disabledTools(),
            null,
            null,
            request.cdpUrl(),
            null
        );
    }

    private String buildMergedMessage(String userMessage, String queuedPrompt, String goal, String subgoals, String subgoal) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("[Standing Goal]\n").append(goal).append("\n\n");
        }
        if (subgoals != null && !subgoals.isBlank()) {
            sb.append("[Subgoals]\n").append(subgoals).append("\n\n");
        }
        if (subgoal != null && !subgoal.isBlank()) {
            sb.append("[Goal/Subgoal focus]\n").append(subgoal).append("\n\n");
        }
        if (queuedPrompt != null && !queuedPrompt.isBlank()) {
            sb.append("[Queued context]\n").append(queuedPrompt).append("\n\n");
        }
        sb.append(userMessage);
        return sb.toString();
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
        Integer maxTokens = null;
        if (maxTokens == null || maxTokens <= 0) {
            try {
                maxTokens = Integer.parseInt(session.metadata().getOrDefault("maxTokens", "0"));
            } catch (NumberFormatException e) {
                maxTokens = 0;
            }
        }
        return new ModelRequestOptions(reasoningEffort, fastMode, request.voiceMode(),
            request.personality(), request.subgoal(), maxTokens);
    }
}
