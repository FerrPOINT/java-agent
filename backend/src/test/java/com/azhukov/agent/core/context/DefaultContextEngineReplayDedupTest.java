package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression (live 2026-08-27, session a8525e04): a mid-turn rotation copied
 * the whole in-flight turn into the child session while mid-turn persistence
 * had already written the user message — the SAME user content sat in history
 * twice. The single-trailing-row dedup left the extra copy in whenever the
 * persisted suffix differed; HistorySanitizer then merged the duplicate user
 * turns and dropped an in-flight tool result as an "orphan" (13 -&gt; 12
 * messages), corrupting the replayed context. Also: persisted SYSTEM rows must
 * never load back as history (they are regenerated each turn by the prompt
 * builder; the old fallback mapped them to USER messages).
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineReplayDedupTest {

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ContextCompressor contextCompressor;
    @Mock
    private SessionLineagePort lineage;

    private DefaultContextEngine engine;
    private Session session;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMaxContextMessages(50);
        properties.getContext().setMaxTokens(100_000);
        properties.getContext().setTargetTokens(80_000);
        engine = new DefaultContextEngine(memoryProvider, skillManager, messageRepository,
            contextCompressor, properties);
        engine.setSessionLineageService(lineage);
        session = new Session(UUID.randomUUID(), "user-1", "chat",
            "openai-compatible", "app-test", null, java.util.Map.of(), null);
    }

    @Test
    void dropsAllTrailingDuplicateUserRows() {
        String userText = "## Current Session Context\n\n**Source:** Telegram";
        // Simulate the live failure: two identical user rows persisted before
        // the incoming turn (mid-turn flush + rotation copy).
        List<Message> history = List.of(
            Message.assistant("речь о предыдущей сессии", 1),
            Message.user(userText),
            Message.user(userText));
        when(lineage.loadMessagesWithAncestors(any(UUID.class))).thenReturn(new java.util.ArrayList<>(history));

        List<Message> out = engine.prepareContext(session, List.of(
            Message.system("sys"), Message.user(userText)));

        long userRows = out.stream().filter(m -> m.role() == Role.USER).count();
        assertThat(userRows).as("incoming user message appears exactly once").isEqualTo(1);
    }

    @Test
    void persistedSystemRowsNeverLoadAsHistory() {
        MessageEntity systemRow = new MessageEntity();
        systemRow.setRole("system");
        systemRow.setContent("You are the assistant... (stale prompt)");
        MessageEntity userRow = new MessageEntity();
        userRow.setRole("user");
        userRow.setContent("hello");

        // Lineage path returns the system row mapped as-is
        when(lineage.loadMessagesWithAncestors(any(UUID.class))).thenReturn(new java.util.ArrayList<>(List.of(
            Message.system("You are the assistant... (stale prompt)"), Message.user("hello"))));

        List<Message> out = engine.prepareContext(session, List.of(
            Message.system("fresh sys"), Message.user("hello")));

        assertThat(out.stream().filter(m -> m.role() == Role.SYSTEM)).hasSize(1);
        assertThat(out.get(0).content()).isEqualTo("fresh sys");
    }

    @Test
    void fallbackPathSkipsSystemRowsEntirely() {
        // No lineage messages → fallback paginated query used
        when(lineage.loadMessagesWithAncestors(any(UUID.class))).thenReturn(null);
        MessageEntity systemRow = new MessageEntity();
        systemRow.setRole("system");
        systemRow.setContent("stale system prompt row");
        MessageEntity userRow = new MessageEntity();
        userRow.setRole("user");
        userRow.setContent("hello");
        when(messageRepository.findBySessionIdOrderByCreatedAtDesc(any(UUID.class), any()))
            .thenReturn(List.of(userRow, systemRow)); // desc: system last written

        List<Message> out = engine.prepareContext(session, List.of(
            Message.system("fresh sys"), Message.user("hello")));

        // The stale system row must NOT appear — not as system, not as user
        assertThat(out).noneMatch(m -> m.content() != null && m.content().contains("stale system prompt"));
        assertThat(out.stream().filter(m -> m.role() == Role.USER).count()).isEqualTo(1);
    }
}
