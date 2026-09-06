package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.core.ports.MessageStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch-coverage tests for DefaultContextEngine — focuses on edge cases:
 * null messages, empty messages, developer role, compression cooldown,
 * preflight checks, token usage tracking, model metadata, and history exceptions.
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineBranchCoverageTest {

    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private com.azhukov.agent.core.ports.MessageStorePort messageRepository;
    @Mock private ContextCompressor contextCompressor;
    @Mock private PromptCacheTracker cacheTracker;
    @Mock private ModelMetadataService modelMetadataService;

    private AgentProperties properties;
    private AgentProperties.ContextProperties contextProps;
    private Session session;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        contextProps = properties.getContext();
        contextProps.setMaxContextMessages(5);
        contextProps.setMaxTokens(100);
        contextProps.setTargetTokens(80);
        session = Session.create("user-42", "openai-compatible", "gpt-4o-mini");
    }

    // ── Developer role system message ──

    @Test
    void prepareContextWithDeveloperRolePreservesDeveloperType() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.developer("You are a dev assistant."), Message.user("Hello"));
        List<Message> result = engine.prepareContext(session, incoming);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).role()).isEqualTo(Role.DEVELOPER);
        assertThat(result.get(0).content()).isEqualTo("You are a dev assistant.");
    }

    @Test
    void prepareContextWithDeveloperRoleAndSkillsDoesNotInjectSkillContent() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        // Hermes parity: the skills INDEX lives in the system prompt's volatile
        // tier (DefaultPromptBuilder.buildSkillsIndex), NOT in prepareContext.
        // Raw SKILL.md content must never leak into the context messages.
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.developer("Dev prompt."), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        assertThat(result.get(0).role()).isEqualTo(Role.DEVELOPER);
        assertThat(result.get(0).content()).isEqualTo("Dev prompt.");
    }

    // ── Skills are no longer injected here (Hermes parity) ──

    @Test
    void prepareContextDoesNotInjectSkillsEvenWhenPresent() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        // The system message must stay byte-identical — no skills block at all
        String systemContent = result.get(0).content();
        assertThat(systemContent).isEqualTo("System");
        assertThat(systemContent).doesNotContain("skill1").doesNotContain("skill4");
    }

    @Test
    void prepareContextWithNullSkillContentStaysClean() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        String systemContent = result.get(0).content();
        assertThat(systemContent).isEqualTo("System");
    }

    @Test
    void prepareContextWithLongSkillContentStaysClean() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        // Use large enough maxTokens to avoid trimming
        properties.getContext().setMaxTokens(10000);
        properties.getContext().setTargetTokens(9000);
        properties.getContext().setMaxContextMessages(50);

        String longContent = "x".repeat(500);
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        String systemContent = result.get(0).content();
        assertThat(systemContent).isEqualTo("System");
    }

    // ── Empty skills list ──

    @Test
    void appendSkillsWithEmptyListDoesNotAddSkillsSection() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("System"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        assertThat(result.get(0).content()).isEqualTo("System");
        assertThat(result.get(0).content()).doesNotContain("Available skills:");
    }

    // ── History load failure ──

    @Test
    void appendRecentHistoryHandlesRepositoryExceptionGracefully() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenThrow(new RuntimeException("DB connection failed"));

        List<Message> incoming = List.of(Message.system("System"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);

        // Should still return messages without history, not throw
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).role()).isEqualTo(Role.SYSTEM);
    }

    // ── Tool role in history ──

    @Test
    void appendRecentHistoryHandlesToolRoleMessage() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);


        MessageEntity toolMsg = new MessageEntity();
        toolMsg.setSessionId(session.id());
        toolMsg.setRole("tool");
        toolMsg.setContent("tool output");
        toolMsg.setTurnIndex(2);
        toolMsg.setToolCallId("call-1");

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), anyInt()))
            .thenReturn(List.of(toolMsg));

        List<Message> incoming = List.of(Message.system("System"), Message.user("Current"));
        List<Message> result = engine.prepareContext(session, incoming);

        // HistorySanitizer (Hermes parity): an orphan tool result without a
        // matching assistant tool_call is DROPPED — strict providers reject
        // such histories with HTTP 400.
        assertThat(result).noneMatch(m -> m.role() == Role.TOOL);
    }

    @Test
    void appendRecentHistoryWithNullTurnIndexDefaultsToZero() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);


        MessageEntity userMsg = new MessageEntity();
        userMsg.setSessionId(session.id());
        userMsg.setRole("user");
        userMsg.setContent("test");
        userMsg.setTurnIndex(null);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), anyInt()))
            .thenReturn(List.of(userMsg));

        List<Message> incoming = List.of(Message.system("System"), Message.user("Current"));
        List<Message> result = engine.prepareContext(session, incoming);

        assertThat(result).isNotEmpty();
    }

    @Test
    void appendRecentHistoryWithNullContentDefaultsToEmpty() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);


        MessageEntity nullContentMsg = new MessageEntity();
        nullContentMsg.setSessionId(session.id());
        nullContentMsg.setRole("user");
        nullContentMsg.setContent(null);
        nullContentMsg.setTurnIndex(1);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(eq(session.id()), anyInt()))
            .thenReturn(List.of(nullContentMsg));

        List<Message> incoming = List.of(Message.system("System"), Message.user("Current"));
        List<Message> result = engine.prepareContext(session, incoming);

        assertThat(result).isNotEmpty();
    }

    // ── shouldCompressPreflight ──

    @Test
    void shouldCompressPreflightNullMessagesReturnsFalse() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        assertThat(engine.shouldCompressPreflight(null)).isFalse();
    }

    @Test
    void shouldCompressPreflightEmptyMessagesReturnsFalse() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        assertThat(engine.shouldCompressPreflight(Collections.emptyList())).isFalse();
    }

    @Test
    void shouldCompressPreflightUnderThresholdReturnsFalse() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        // Small messages under threshold
        List<Message> smallMessages = List.of(Message.user("hi"));
        assertThat(engine.shouldCompressPreflight(smallMessages)).isFalse();
    }

    @Test
    void shouldCompressPreflightUsesCurrentContextNotStalePriorUsage() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        // Simulate a large preceding request. The new one is tiny and must not
        // rotate merely because it follows a large prompt.
        engine.updateFromResponse(new TokenUsage(10_000, 1, 10_001, 0, 0, 0));

        assertThat(engine.shouldCompressPreflight(List.of(Message.user("short follow-up")))).isFalse();
    }

    @Test
    void shouldCompressPreflightOverThresholdReturnsTrue() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        // maxTokens=100, threshold = 100 * 0.75 = 75 tokens = 300 chars
        String bigContent = "x".repeat(500);
        List<Message> bigMessages = List.of(Message.user(bigContent));
        assertThat(engine.shouldCompressPreflight(bigMessages)).isTrue();
    }

    // ── Compression cooldown ──

    @Test
    void prepareContextSkipsCompressionWithinCooldown() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties, cacheTracker);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        // Set lastCompressedAt to now (within cooldown)
        // We trigger compression first time
        String bigContent = "x".repeat(500);
        Message systemMessage = Message.system(bigContent);
        Message userMessage = Message.user("hi");

        when(contextCompressor.compress(any(), anyInt()))
            .thenReturn(List.of(systemMessage, userMessage));

        // First call triggers compression
        engine.prepareContext(session, List.of(systemMessage, userMessage));
        verify(contextCompressor).compress(any(), anyInt());

        // Second call should skip compression due to cooldown
        org.mockito.Mockito.clearInvocations(contextCompressor);
        engine.prepareContext(session, List.of(systemMessage, userMessage));
        verify(contextCompressor, never()).compress(any(), anyInt());
    }

    // ── updateFromResponse ──

    @Test
    void updateFromResponseNullUsageIsNoOp() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        engine.updateFromResponse(null);
        // Should not throw
        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("lastPromptTokens")).isEqualTo(0);
    }

    @Test
    void updateFromResponseUpdatesAllFields() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        TokenUsage usage = new TokenUsage(100, 50, 150, 10, 20, 5);
        engine.updateFromResponse(usage);

        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("lastPromptTokens")).isEqualTo(100);
        assertThat(status.get("lastCompletionTokens")).isEqualTo(50);
        assertThat(status.get("lastTotalTokens")).isEqualTo(150);
        assertThat(status.get("lastCacheReadTokens")).isEqualTo(10);
        assertThat(status.get("lastCacheWriteTokens")).isEqualTo(20);
        assertThat(status.get("lastReasoningTokens")).isEqualTo(5);
    }

    // ── getStatus with model metadata ──

    @Test
    void getStatusWithModelMetadataReturnsUsagePercent() {
        when(modelMetadataService.detectContextLength(anyString())).thenReturn(8192);
        properties.getModel().setModelName("gpt-4o");

        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor,
            properties, null, modelMetadataService);

        engine.updateFromResponse(new TokenUsage(4096, 100, 4196, 0, 0, 0));

        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("contextLength")).isEqualTo(8192);
        // rev-129: full pipeline resolution — reserved output (4096) leaves an
        // effective budget of 4096; the 64K floor degenerates and the 85% rule
        // applies inside that budget: min(4096*0.85, 4095).
        int effectiveBudget2 = 8192 - properties.getModel().getMaxTokens();
        assertThat(status.get("thresholdTokens")).isEqualTo(Math.min((int)(effectiveBudget2 * 0.85), effectiveBudget2 - 1));
        assertThat(status.get("usagePercent")).asString().contains("50").contains("%");
    }

    @Test
    void getStatusWithoutModelMetadataReturnsZeroUsagePercent() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("usagePercent")).asString().contains("0").contains("%");
    }

    // ── updateModel ──

    @Test
    void updateModelWithNullDoesNotUpdate() {
        when(modelMetadataService.detectContextLength(anyString())).thenReturn(4096);
        properties.getModel().setModelName("initial-model");

        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor,
            properties, null, modelMetadataService);

        int initialContextLength = (int) engine.getStatus().get("contextLength");

        engine.updateModel(null);
        // Context length should not change
        assertThat(engine.getStatus().get("contextLength")).isEqualTo(initialContextLength);
    }

    @Test
    void updateModelWithBlankDoesNotUpdate() {
        when(modelMetadataService.detectContextLength(anyString())).thenReturn(4096);
        properties.getModel().setModelName("initial-model");

        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor,
            properties, null, modelMetadataService);

        int initialContextLength = (int) engine.getStatus().get("contextLength");
        engine.updateModel("  ");
        assertThat(engine.getStatus().get("contextLength")).isEqualTo(initialContextLength);
    }

    @Test
    void updateModelWithValidNameUpdatesContextLength() {
        when(modelMetadataService.detectContextLength(anyString())).thenReturn(16384);
        properties.getModel().setModelName("initial-model");

        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor,
            properties, null, modelMetadataService);

        engine.updateModel("new-model");
        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("contextLength")).isEqualTo(16384);
        // rev-129: threshold now resolves through the full Hermes pipeline
        // (CompressionPolicy.computeThresholdTokens): the model's reserved
        // output budget (agent.model.max-tokens, default 4096) is subtracted
        // first (#43547), then the 64K floor degenerates (>= effective window
        // 12288) and the 85% trigger rule applies: min(12288*0.85, 12287).
        int effectiveBudget = 16384 - properties.getModel().getMaxTokens();
        assertThat(status.get("thresholdTokens"))
            .isEqualTo(Math.min((int) (effectiveBudget * 0.85), effectiveBudget - 1));
    }

    @Test
    void updateModelWithoutModelMetadataServiceIsNoOp() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        // No modelMetadataService → updateModel should be no-op
        engine.updateModel("new-model");
        assertThat(engine.getStatus().get("contextLength")).isEqualTo(0);
    }

    // ── getLastCompressionAt ──

    @Test
    void getLastCompressionAtReturnsNullWhenNeverCompressed() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        assertThat(engine.getLastCompressionAt(session.id())).isNull();
    }

    // ── TrimToFit edge cases ──

    @Test
    void trimToFitWithMaxContextMessagesZeroDefaultsToFifty() {
        // Setting maxContextMessages to 0 should default to 50
        contextProps.setMaxContextMessages(0);
        contextProps.setMaxTokens(10000); // Large enough to not trigger char-based trimming
        contextProps.setTargetTokens(9000);

        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        // Create 10 messages — all under limit with maxContextMessages=50
        List<Message> messages = new java.util.ArrayList<>();
        messages.add(Message.system("sys"));
        for (int i = 0; i < 9; i++) {
            messages.add(Message.user("msg-" + i));
        }
        List<Message> result = engine.prepareContext(session, messages);
        // Should not trim (10 <= 50), but HistorySanitizer merges the 9
        // consecutive user messages into one (Hermes parity) — 2 remain.
        assertThat(result).hasSize(2);
        assertThat(result.get(1).content()).contains("msg-0").contains("msg-8");
    }

    // ── Constructor with cacheTracker only ──

    @Test
    void constructorWithCacheTrackerOnlyWorks() {
        DefaultContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties, cacheTracker);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> incoming = List.of(Message.system("Sys"), Message.user("Hi"));
        List<Message> result = engine.prepareContext(session, incoming);
        assertThat(result).isNotEmpty();
    }

    // ── ContextEngine interface test ──

    @Test
    void contextEngineInterfaceDefaultPrepareContextReturnsMessages() {
        ContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);

        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), anyInt()))
            .thenReturn(Collections.emptyList());

        List<Message> messages = List.of(Message.user("test"));
        List<Message> result = engine.prepareContext(session, messages);
        assertThat(result).isNotEmpty();
    }

    @Test
    void contextEngineInterfaceDefaultShouldCompressPreflight() {
        ContextEngine engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        assertThat(engine.shouldCompressPreflight(null)).isFalse();
        assertThat(engine.shouldCompressPreflight(Collections.emptyList())).isFalse();
    }
}