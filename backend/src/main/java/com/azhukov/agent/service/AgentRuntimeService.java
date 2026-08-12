package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.*;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.AgentSessionResolver;
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
    private final TransactionTemplate transactionTemplate;
    private final AgentSessionResolver sessionResolver;
    private final CliStateApplier cliStateApplier;
    private final SessionCompressionHelper sessionCompressionHelper;

    private static final String UNKNOWN_MODEL = "unknown";

    public ChatResponseDto runDelegate(ChatRequest request) {
        int depth = request.delegationDepth() != null ? request.delegationDepth() : 0;
        Session session = createSession("user-1", "openai-compatible", "")
            .withMetadata("delegation_depth", String.valueOf(depth));

        TurnResult result = agentRuntime.runTurn(session, request.message(), List.of(),
            ModelRequestOptions.empty());
        persistMessages(session.id(), result.messages());

        return buildResponse(session, result, false);
    }

    public ChatResponseDto runTurn(ChatRequest request) {
        ChatRequest applied = applyCliState(request);
        var resolved = sessionResolver.resolveOrCreate(
            applied.sessionId(), "user-1", properties.getModel().getModelName());
        boolean isNew = resolved.isNew();
        Session session = resolved.session();

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
        return sessionRepository.findAllByUserId("user-1", PageRequest.of(0, 50)).stream()
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
        return sessionRepository.findAllByUserId("user-1", PageRequest.of(0, 50)).stream()
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
        return new ModelRequestOptions(reasoningEffort, fastMode, request.voiceMode(),
            request.personality(), request.subgoal(), maxTokens);
    }
}
