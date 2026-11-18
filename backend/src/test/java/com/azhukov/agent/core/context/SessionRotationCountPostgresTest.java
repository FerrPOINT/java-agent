package com.azhukov.agent.core.context;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class SessionRotationCountPostgresTest extends PostgresTestContainer {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("contextCompressor") DefaultContextCompressor compressor;
    @Autowired com.azhukov.agent.tools.memory.SessionSearchTool search;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;

    @Test
    void rotationPersistsHandoffAndAccurateCountWhileKeepingParentHistory() throws Exception {
        SessionEntity parent = new SessionEntity();
        parent.setUserId("rotation-owner");
        parent.setTitle("Rotation fixture");
        parent.setModelProvider("noop");
        parent.setModelName("noop");
        parent.setSource("telegram");
        parent.setCreatedAt(Instant.now());
        parent.setUpdatedAt(Instant.now());
        UUID parentId = sessions.saveAndFlush(parent).getId();
        MessageEntity original = new MessageEntity();
        original.setSessionId(parentId);
        original.setRole("user");
        original.setContent("original");
        original.setTurnIndex(0);
        original.setCreatedAt(Instant.now());
        messages.saveAndFlush(original);

        var result = compressor.rotateSession(parentId.toString(), List.of(
            Message.system("ordinary system"), Message.user("handoff"), Message.assistant("answer", 1)));
        assertThat(result).isPresent();
        UUID childId = result.orElseThrow().newSessionId();
        messages.flush();
        assertThat(messages.countBySessionId(parentId)).isEqualTo(1);
        assertThat(messages.countBySessionId(childId)).isEqualTo(2);
        assertThat(sessions.findById(childId).orElseThrow().getMessageCount()).isEqualTo(2);
        com.azhukov.agent.core.security.UserContext.set("rotation-owner", com.azhukov.agent.core.security.UserContext.ROLE_USER);
        try {
            var parentRead = objectMapper.readTree(search.execute("{\"session_id\":\"" + parentId + "\"}", null, null).content());
            var childRead = objectMapper.readTree(search.execute("{\"session_id\":\"" + childId + "\"}", null, null).content());
            assertThat(parentRead.path("messages")).hasSize(1);
            assertThat(parentRead.path("messages").get(0).path("content").asText()).isEqualTo("original");
            assertThat(childRead.path("messages")).hasSize(2);
            assertThat(childRead.path("messages").toString()).contains("handoff", "answer");
            var discovered = objectMapper.readTree(search.execute("{\"query\":\"original\"}", null, null).content());
            assertThat(discovered.path("count").asInt()).isGreaterThanOrEqualTo(1);
        } finally {
            com.azhukov.agent.core.security.UserContext.clear();
        }
    }
}
