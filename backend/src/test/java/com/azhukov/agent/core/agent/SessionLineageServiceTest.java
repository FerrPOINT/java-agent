package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SessionLineageService} — session lineage walking and
 * ancestor message loading. Mirrors Hermes {@code _session_lineage_root_to_tip}
 * and {@code get_messages_as_conversation(include_ancestors=True)}.
 */
class SessionLineageServiceTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private MessageMapper messageMapper;
    private TransactionTemplate transactionTemplate;
    private SessionLineageService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        service = new SessionLineageService(sessionRepository, messageRepository, messageMapper, transactionTemplate);
    }

    // ── findAncestorSessionIds ──

    @Test
    void findAncestorSessionIds_nullReturnsEmptyList() {
        assertThat(service.findAncestorSessionIds(null)).isEmpty();
    }

    @Test
    void findAncestorSessionIds_noParentReturnsSelfOnly() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId, null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        List<UUID> result = service.findAncestorSessionIds(sessionId);

        assertThat(result).containsExactly(sessionId);
    }

    @Test
    void findAncestorSessionIds_singleParentReturnsTwoElementChain() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity parent = newSessionEntity(parentId, null);
        SessionEntity child = newSessionEntity(childId, parentId);

        when(sessionRepository.findById(childId)).thenReturn(Optional.of(child));
        when(sessionRepository.findById(parentId)).thenReturn(Optional.of(parent));

        List<UUID> result = service.findAncestorSessionIds(childId);

        // Root-to-tip order: [parent, child]
        assertThat(result).containsExactly(parentId, childId);
    }

    @Test
    void findAncestorSessionIds_threeLevelChainReturnsRootToTip() {
        UUID rootId = UUID.randomUUID();
        UUID middleId = UUID.randomUUID();
        UUID tipId = UUID.randomUUID();
        SessionEntity root = newSessionEntity(rootId, null);
        SessionEntity middle = newSessionEntity(middleId, rootId);
        SessionEntity tip = newSessionEntity(tipId, middleId);

        when(sessionRepository.findById(tipId)).thenReturn(Optional.of(tip));
        when(sessionRepository.findById(middleId)).thenReturn(Optional.of(middle));
        when(sessionRepository.findById(rootId)).thenReturn(Optional.of(root));

        List<UUID> result = service.findAncestorSessionIds(tipId);

        // Root-to-tip order: [root, middle, tip]
        assertThat(result).containsExactly(rootId, middleId, tipId);
    }

    @Test
    void findAncestorSessionIds_cycleDetectedAndStops() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        SessionEntity a = newSessionEntity(idA, idB);
        SessionEntity b = newSessionEntity(idB, idA);

        when(sessionRepository.findById(idA)).thenReturn(Optional.of(a));
        when(sessionRepository.findById(idB)).thenReturn(Optional.of(b));

        List<UUID> result = service.findAncestorSessionIds(idA);

        // Should stop after seeing both nodes (cycle detected)
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(idB);
        assertThat(result.get(1)).isEqualTo(idA);
    }

    @Test
    void findAncestorSessionIds_sessionNotFoundReturnsSelfOnly() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        List<UUID> result = service.findAncestorSessionIds(sessionId);

        assertThat(result).containsExactly(sessionId);
    }

    // ── hasParentSession ──

    @Test
    void hasParentSession_nullReturnsFalse() {
        assertThat(service.hasParentSession(null)).isFalse();
    }

    @Test
    void hasParentSession_noParentReturnsFalse() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = newSessionEntity(sessionId, null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        assertThat(service.hasParentSession(sessionId)).isFalse();
    }

    @Test
    void hasParentSession_withParentReturnsTrue() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity child = newSessionEntity(childId, parentId);
        when(sessionRepository.findById(childId)).thenReturn(Optional.of(child));

        assertThat(service.hasParentSession(childId)).isTrue();
    }

    @Test
    void hasParentSession_sessionNotFoundReturnsFalse() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThat(service.hasParentSession(sessionId)).isFalse();
    }

    // ── loadMessagesWithAncestors ──

    @Test
    void loadMessagesWithAncestors_nullReturnsEmptyList() {
        assertThat(service.loadMessagesWithAncestors(null)).isEmpty();
    }

    @Test
    void loadMessagesWithAncestors_noParentLoadsCurrentOnly() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        MessageEntity archived = newMessageEntity(sessionId, "user", "Archived", 0);
        archived.setActive(false);
        archived.setCompacted(true);
        List<MessageEntity> messages = List.of(
            archived,
            newMessageEntity(sessionId, "user", "Hello", 0),
            newMessageEntity(sessionId, "assistant", "Hi there", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(messages);

        List<Message> result = service.loadMessagesWithAncestors(sessionId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(Role.USER);
        assertThat(result.get(0).content()).isEqualTo("Hello");
        assertThat(result.get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(result.get(1).content()).isEqualTo("Hi there");
    }

    @Test
    void loadMessagesWithAncestors_withParentCombinesAncestorAndCurrentMessages() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity parent = newSessionEntity(parentId, null);
        SessionEntity child = newSessionEntity(childId, parentId);
        when(sessionRepository.findById(childId)).thenReturn(Optional.of(child));
        when(sessionRepository.findById(parentId)).thenReturn(Optional.of(parent));

        // Parent has older messages
        List<MessageEntity> parentMessages = List.of(
            newMessageEntity(parentId, "user", "Original question", 0),
            newMessageEntity(parentId, "assistant", "Original answer", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(parentId)).thenReturn(parentMessages);

        // Child has newer messages (post-rotation)
        List<MessageEntity> childMessages = List.of(
            newMessageEntity(childId, "user", "Follow-up question", 0),
            newMessageEntity(childId, "assistant", "Follow-up answer", 1)
        );
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(childId)).thenReturn(childMessages);

        List<Message> result = service.loadMessagesWithAncestors(childId);

        // Root-to-tip: parent messages first, then child messages
        assertThat(result).hasSize(4);
        assertThat(result.get(0).content()).isEqualTo("Original question");
        assertThat(result.get(1).content()).isEqualTo("Original answer");
        assertThat(result.get(2).content()).isEqualTo("Follow-up question");
        assertThat(result.get(3).content()).isEqualTo("Follow-up answer");
    }

    @Test
    void loadMessagesWithAncestors_threeLevelChainCombinesAllMessages() {
        UUID rootId = UUID.randomUUID();
        UUID middleId = UUID.randomUUID();
        UUID tipId = UUID.randomUUID();
        SessionEntity root = newSessionEntity(rootId, null);
        SessionEntity middle = newSessionEntity(middleId, rootId);
        SessionEntity tip = newSessionEntity(tipId, middleId);
        when(sessionRepository.findById(tipId)).thenReturn(Optional.of(tip));
        when(sessionRepository.findById(middleId)).thenReturn(Optional.of(middle));
        when(sessionRepository.findById(rootId)).thenReturn(Optional.of(root));

        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(rootId)).thenReturn(List.of(
            newMessageEntity(rootId, "user", "Root msg", 0)
        ));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(middleId)).thenReturn(List.of(
            newMessageEntity(middleId, "user", "Middle msg", 0)
        ));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(tipId)).thenReturn(List.of(
            newMessageEntity(tipId, "user", "Tip msg", 0)
        ));

        List<Message> result = service.loadMessagesWithAncestors(tipId);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).content()).isEqualTo("Root msg");
        assertThat(result.get(1).content()).isEqualTo("Middle msg");
        assertThat(result.get(2).content()).isEqualTo("Tip msg");
    }

    @Test
    void loadMessagesWithAncestors_emptySessionsReturnsEmptyList() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity session = newSessionEntity(sessionId, null);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(Collections.emptyList());

        List<Message> result = service.loadMessagesWithAncestors(sessionId);

        assertThat(result).isEmpty();
    }

    @Test
    void loadMessagesWithAncestors_parentHasNoMessagesStillLoadsChildMessages() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity parent = newSessionEntity(parentId, null);
        SessionEntity child = newSessionEntity(childId, parentId);
        when(sessionRepository.findById(childId)).thenReturn(Optional.of(child));
        when(sessionRepository.findById(parentId)).thenReturn(Optional.of(parent));

        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(parentId)).thenReturn(Collections.emptyList());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(childId)).thenReturn(List.of(
            newMessageEntity(childId, "user", "Child only", 0)
        ));

        List<Message> result = service.loadMessagesWithAncestors(childId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Child only");
    }

    // ── Helpers ──

    private SessionEntity newSessionEntity(UUID id, UUID parentSessionId) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId("user-1");
        e.setModelProvider("openai-compatible");
        e.setModelName("test-model");
        e.setTitle("Test");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setParentSessionId(parentSessionId);
        return e;
    }

    private MessageEntity newMessageEntity(UUID sessionId, String role, String content, int turnIndex) {
        MessageEntity e = new MessageEntity();
        e.setId(UUID.randomUUID());
        e.setSessionId(sessionId);
        e.setRole(role);
        e.setContent(content);
        e.setTurnIndex(turnIndex);
        e.setCreatedAt(Instant.now());
        return e;
    }
}
