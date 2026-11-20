package com.azhukov.agent.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class SessionCompressionCountPostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired SessionCompressionHelper helper;
    @Autowired com.azhukov.agent.tools.memory.SessionSearchTool searchTool;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @org.junit.jupiter.api.AfterEach
    void clearIdentity() { com.azhukov.agent.core.security.UserContext.clear(); }

    @Test
    void compressedHistoryRemainsSearchableReadableAndScrollableByOwner() throws Exception {
        Instant now = Instant.now();
        SessionEntity session = new SessionEntity();
        session.setUserId("compression-owner");
        session.setTitle("Compression fixture");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(now.minusSeconds(20));
        session.setUpdatedAt(now.minusSeconds(20));
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity old = new MessageEntity();
        old.setSessionId(id);
        old.setRole("user");
        old.setContent("compressedneedle original");
        old.setTurnIndex(0);
        old.setCreatedAt(now.minusSeconds(10));
        UUID oldId = messages.saveAndFlush(old).getId();
        helper.persistCompressed(id, List.of(Message.assistant("summary", 1)), now, Set.of(oldId));
        messages.flush();
        com.azhukov.agent.core.security.UserContext.set("compression-owner", "user");
        var discovery = objectMapper.readTree(searchTool.execute("{\"query\":\"compressedneedle\"}", null, null).content());
        assertThat(discovery.path("success").asBoolean()).isTrue();
        assertThat(discovery.path("results")).hasSize(1);
        assertThat(discovery.path("results").get(0).path("session_id").asText()).isEqualTo(id.toString());
        var read = objectMapper.readTree(searchTool.execute("{\"session_id\":\"" + id + "\"}", null, null).content());
        assertThat(read.path("message_count").asInt()).isEqualTo(2);
        assertThat(read.path("messages").toString()).contains("compressedneedle original", "summary");
        var scroll = objectMapper.readTree(searchTool.execute("{\"session_id\":\"" + id
            + "\",\"around_message_id\":\"" + oldId + "\"}", null, null).content());
        assertThat(scroll.path("success").asBoolean()).isTrue();
        assertThat(scroll.path("messages").toString()).contains("compressedneedle original");
    }

    @Test
    void compressedMessagesAndConcurrentTailRetainTheirOrder() {
        Instant now = Instant.now();
        SessionEntity session = new SessionEntity();
        session.setUserId("compression-order-owner");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(now.minusSeconds(20));
        session.setUpdatedAt(now.minusSeconds(20));
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity original = new MessageEntity();
        original.setSessionId(id); original.setRole("user"); original.setContent("old");
        original.setCreatedAt(now.minusSeconds(10)); original.setTurnIndex(0);
        UUID originalId = messages.saveAndFlush(original).getId();
        MessageEntity tail = new MessageEntity();
        tail.setSessionId(id); tail.setRole("user"); tail.setContent("concurrent-tail");
        tail.setCreatedAt(now.minusSeconds(1)); tail.setTurnIndex(1);
        messages.saveAndFlush(tail);
        helper.persistCompressed(id, List.of(Message.system("summary"), Message.user("retained-user"),
            Message.assistant("retained-assistant", 1)), now, Set.of(originalId));
        messages.flush();
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id).stream()
            .filter(e -> !Boolean.FALSE.equals(e.getActive())).map(MessageEntity::getContent))
            .containsExactly("summary", "retained-user", "retained-assistant", "concurrent-tail");
    }

    @Test
    void retainedArchiveAndSummaryAreBothIncludedInSessionCount() {
        SessionEntity session = new SessionEntity();
        session.setUserId("compression-owner");
        session.setTitle("Compression fixture");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity old = new MessageEntity();
        old.setSessionId(id);
        old.setRole("user");
        old.setContent("original");
        old.setTurnIndex(0);
        old.setCreatedAt(Instant.now().minusSeconds(10));
        UUID oldId = messages.saveAndFlush(old).getId();
        helper.persistCompressed(id, List.of(Message.assistant("summary", 1)), Instant.now(), Set.of(oldId));
        messages.flush();
        assertThat(messages.countBySessionId(id)).isEqualTo(2);
        assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(2);
    }
}
