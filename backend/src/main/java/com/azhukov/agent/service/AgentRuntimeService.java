package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.ApproveMemoryRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.MemoryDto;
import com.azhukov.agent.api.dto.PendingMemoryDto;
import com.azhukov.agent.api.dto.RejectMemoryRequest;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
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

    private static final String UNKNOWN_MODEL = "unknown";

    @Transactional
    public ChatResponseDto runDelegate(ChatRequest request) {
        int depth = request.delegationDepth() != null ? request.delegationDepth() : 0;
        Session session = createSession("user-1", "openai-compatible", "")
            .withMetadata("delegation_depth", String.valueOf(depth));

        TurnResult result = agentRuntime.runTurn(session, request.message());
        persistMessages(session.id(), result.messages());

        return buildResponse(session, result, false);
    }

    @Transactional
    public ChatResponseDto runTurn(ChatRequest request) {
        boolean isNew = request.sessionId() == null;
        Session session = isNew
            ? createSession("user-1", "openai-compatible", "")
            : loadSession(request.sessionId());

        TurnResult result = agentRuntime.runTurn(session, request.message());
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
            .map(e -> new SessionSummaryDto(
                e.getId(),
                e.getUserId(),
                e.getTitle(),
                e.getModelProvider(),
                e.getModelName(),
                e.getCreatedAt(),
                e.getUpdatedAt()
            ))
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
    public List<SessionSummaryDto> listSessionsByUserId(String userId) {
        return sessionRepository.findAllByUserId(userId).stream()
            .map(e -> new SessionSummaryDto(
                e.getId(),
                e.getUserId(),
                e.getTitle(),
                e.getModelProvider(),
                e.getModelName(),
                e.getCreatedAt(),
                e.getUpdatedAt()
            ))
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
            .map(e -> switch (e.getRole()) {
                case "assistant" -> Message.assistant(e.getContent() != null ? e.getContent() : "",
                    e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                case "tool" -> Message.toolResult(e.getToolCallId(),
                    e.getContent() != null ? e.getContent() : "",
                    e.getTurnIndex() != null ? e.getTurnIndex() : 0);
                default -> Message.user(e.getContent() != null ? e.getContent() : "");
            })
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
            MessageEntity e = new MessageEntity();
            e.setSessionId(sessionId);
            e.setRole(m.role().name().toLowerCase());
            e.setContent(m.content());
            e.setCreatedAt(now);
            e.setTurnIndex(m.turnIndex() != null ? m.turnIndex() : 0);
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
        return List.of();
    }

    @Transactional(readOnly = true)
    public InsightsDto getInsights() {
        return usageTracker.getInsights(null);
    }

    @Transactional
    public void restart() {
        // Drain + restart — just clear all session messages for simplicity
        // In production this would coordinate with agent runtime
    }

    @Transactional
    public void reloadMcp() {
        // No-op stub — MCP reload would happen in McpLifecycleManager
    }

    @Transactional
    public void reloadSkills() {
        // No-op stub — skills reload would happen in SkillManager
    }

    @Transactional(readOnly = true)
    public List<String> listBundles() {
        return List.of(); // Stub — no bundle system yet
    }

    @Transactional
    public SessionSummaryDto branchSession(UUID sessionId, String name) {
        // Fork a session: load messages, create new session with copied messages
        SessionEntity source = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        SessionEntity branch = new SessionEntity();
        branch.setUserId(source.getUserId());
        branch.setModelProvider(source.getModelProvider());
        branch.setModelName(source.getModelName());
        branch.setTitle(name != null ? name : "Branch of " + source.getTitle());
        branch.setCreatedAt(Instant.now());
        branch.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(branch);
        // Copy messages
        List<MessageEntity> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        for (MessageEntity m : messages) {
            MessageEntity copy = new MessageEntity();
            copy.setSessionId(saved.getId());
            copy.setRole(m.getRole());
            copy.setContent(m.getContent());
            copy.setToolCallId(m.getToolCallId());
            copy.setToolCallName(m.getToolCallName());
            copy.setToolCallArguments(m.getToolCallArguments());
            copy.setCreatedAt(m.getCreatedAt());
            copy.setTurnIndex(m.getTurnIndex());
            messageRepository.save(copy);
        }
        return new SessionSummaryDto(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), saved.getCreatedAt(), saved.getUpdatedAt());
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

    private Session createSession(String userId, String provider, String modelName) {
        SessionEntity e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(e);
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, java.util.Map.of());
    }

    private Session loadSession(UUID id) {
        SessionEntity e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        return new Session(e.getId(), e.getUserId(), e.getTitle(), e.getModelProvider(), e.getModelName(), null, java.util.Map.of());
    }

    private void persistMessages(UUID sessionId, List<Message> messages) {
        Instant now = Instant.now();
        for (Message m : messages) {
            MessageEntity e = new MessageEntity();
            e.setSessionId(sessionId);
            e.setRole(m.role().name().toLowerCase());
            e.setContent(m.content());
            e.setToolCallId(m.toolCallId());
            if (m.toolCall() != null) {
                e.setToolCallName(m.toolCall().name());
                e.setToolCallArguments(m.toolCall().arguments());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                e.setToolCallName(m.toolCalls().get(0).name());
                e.setToolCallArguments(m.toolCalls().get(0).arguments());
            }
            e.setCreatedAt(now);
            Integer turnIndex = m.turnIndex();
            e.setTurnIndex(turnIndex != null ? turnIndex : 0);
            messageRepository.save(e);
        }
    }
}
