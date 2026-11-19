package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.tools.memory.SessionSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class SessionSearchRecentPostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired SessionSearchTool tool;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @AfterEach void clearIdentity() { UserContext.clear(); }

    @Test
    void recentSessionsAreSelectedWithinOwnerBeforeApplyingLimit() throws Exception {
        Instant now = Instant.now();
        SessionEntity own = create("owner", now.minusSeconds(100));
        sessions.saveAndFlush(own);
        for (int i = 0; i < 20; i++) sessions.save(create("other", now.minusSeconds(i)));
        sessions.flush();
        UserContext.set("owner", UserContext.ROLE_USER);
        var result = tool.execute("{}", null, null);
        JsonNode body = mapper.readTree(result.content());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("results")).hasSize(1);
        assertThat(body.path("results").get(0).path("session_id").asText()).isEqualTo(own.getId().toString());
    }

    @Test
    void discoveryConcealsForeignAncestorMetadataInPostgres() throws Exception {
        Instant now = Instant.now();
        SessionEntity foreign = create("other", now.minusSeconds(20));
        foreign.setTitle("Private ancestor title");
        foreign = sessions.saveAndFlush(foreign);
        SessionEntity own = create("owner", now.minusSeconds(10));
        own.setParentSessionId(foreign.getId());
        own = sessions.saveAndFlush(own);
        MessageEntity hit = new MessageEntity();
        hit.setSessionId(own.getId());
        hit.setRole("user");
        hit.setContent("uncommonsearchword");
        hit.setTurnIndex(0);
        hit.setCreatedAt(now);
        messages.saveAndFlush(hit);
        UserContext.set("owner", UserContext.ROLE_USER);
        JsonNode body = mapper.readTree(tool.execute("{\"query\":\"uncommonsearchword\"}", null, null).content());
        assertThat(body.path("results")).hasSize(1);
        JsonNode match = body.path("results").get(0);
        assertThat(match.path("session_id").asText()).isEqualTo(own.getId().toString());
        assertThat(match.path("title").asText()).isNotEqualTo("Private ancestor title");
        assertThat(match.path("parent_session_id").asText()).isNotEqualTo(foreign.getId().toString());
    }

    @Test
    void readReturnsConversationInTurnOrderWhenMessagesShareTimestamp() throws Exception {
        Instant time = Instant.now();
        SessionEntity session = sessions.saveAndFlush(create("owner", time));
        for (int i = 0; i < 4; i++) {
            MessageEntity message = new MessageEntity();
            message.setSessionId(session.getId());
            message.setRole(i % 2 == 0 ? "user" : "assistant");
            message.setContent("step-" + i);
            message.setTurnIndex(i);
            message.setCreatedAt(time);
            messages.save(message);
        }
        messages.flush();
        UserContext.set("owner", UserContext.ROLE_USER);
        JsonNode body = mapper.readTree(tool.execute("{\"session_id\":\"" + session.getId() + "\"}", null, null).content());
        assertThat(body.path("messages")).hasSize(4);
        for (int i = 0; i < 4; i++)
            assertThat(body.path("messages").get(i).path("content").asText()).isEqualTo("step-" + i);
    }

    @Test
    void emptyMissingInvalidAndForeignSessionsRemainDistinctAndConcealed() throws Exception {
        SessionEntity own = sessions.saveAndFlush(create("owner", Instant.now()));
        SessionEntity foreign = sessions.saveAndFlush(create("other", Instant.now()));
        UserContext.set("owner", UserContext.ROLE_USER);
        JsonNode empty = call("{\"session_id\":\"" + own.getId() + "\"}");
        assertThat(empty.path("success").asBoolean()).isTrue();
        assertThat(empty.path("message_count").asInt()).isZero();
        assertThat(empty.path("messages")).isEmpty();
        JsonNode missing = call("{\"session_id\":\"" + java.util.UUID.randomUUID() + "\"}");
        JsonNode forbidden = call("{\"session_id\":\"" + foreign.getId() + "\"}");
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(forbidden.path("success").asBoolean()).isFalse();
        assertThat(missing.path("error").asText()).startsWith("session_id not found:");
        assertThat(forbidden.path("error").asText()).startsWith("session_id not found:");
        JsonNode invalid = call("{\"session_id\":\"" + own.getId() + "\",\"around_message_id\":\"1\"}");
        assertThat(invalid.path("success").asBoolean()).isFalse();
        assertThat(invalid.path("error").asText()).contains("Invalid around_message_id format");
    }

    @Test
    void longHistoryReadAndScrollRetrieveEveryMessage() throws Exception {
        Instant now = Instant.now();
        SessionEntity own = sessions.saveAndFlush(create("owner", now));
        for (int i = 0; i < 45; i++) {
            MessageEntity row = new MessageEntity();
            row.setSessionId(own.getId());
            row.setRole(i % 2 == 0 ? "user" : "assistant");
            row.setContent("message-" + i);
            row.setTurnIndex(i);
            row.setCreatedAt(now.plusSeconds(i));
            messages.save(row);
        }
        messages.flush();
        UserContext.set("owner", UserContext.ROLE_USER);
        JsonNode read = call("{\"session_id\":\"" + own.getId() + "\"}");
        assertThat(read.path("truncated").asBoolean()).isTrue();
        assertThat(read.path("message_count").asInt()).isEqualTo(45);
        assertThat(read.path("messages").get(29).path("content").asText()).isEqualTo("message-44");
        var seen = new java.util.LinkedHashSet<String>();
        for (JsonNode row : read.path("messages")) seen.add(row.path("content").asText());
        String anchor = read.path("messages").get(19).path("id").asText();
        for (int attempt = 0; attempt < 6 && seen.size() < 45; attempt++) {
            JsonNode scroll = call("{\"session_id\":\"" + own.getId() + "\",\"around_message_id\":\"" + anchor + "\",\"window\":5}");
            assertThat(scroll.path("success").asBoolean()).isTrue();
            JsonNode window = scroll.path("messages");
            for (JsonNode row : window) seen.add(row.path("content").asText());
            anchor = window.get(window.size() - 1).path("id").asText();
        }
        assertThat(seen).hasSize(45);
        for (int i = 0; i < 45; i++) assertThat(seen).contains("message-" + i);
    }

    @Test
    void equalTimestampMessagesRemainStableAcrossReadAndPagination() throws Exception {
        Instant timestamp = Instant.now();
        SessionEntity own = sessions.saveAndFlush(create("owner", timestamp));
        for (int i = 0; i < 45; i++) {
            MessageEntity row = new MessageEntity();
            row.setSessionId(own.getId());
            row.setRole(i % 2 == 0 ? "user" : "assistant");
            row.setContent("tied-" + i);
            row.setTurnIndex(i);
            row.setCreatedAt(timestamp);
            messages.save(row);
        }
        messages.flush();
        UserContext.set("owner", UserContext.ROLE_USER);
        JsonNode read = call("{\"session_id\":\"" + own.getId() + "\"}");
        assertThat(read.path("message_count").asInt()).isEqualTo(45);
        var seen = new java.util.HashSet<String>();
        for (JsonNode row : read.path("messages")) seen.add(row.path("id").asText());
        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_bitmapscan = off");
        String anchor = read.path("messages").get(19).path("id").asText();
        for (int attempt = 0; attempt < 6 && seen.size() < 45; attempt++) {
            JsonNode scroll = call("{\"session_id\":\"" + own.getId() + "\",\"around_message_id\":\"" + anchor + "\",\"window\":5}");
            assertThat(scroll.path("success").asBoolean()).isTrue();
            JsonNode window = scroll.path("messages");
            for (JsonNode row : window) seen.add(row.path("id").asText());
            anchor = window.get(window.size() - 1).path("id").asText();
        }
        assertThat(seen).hasSize(45);
        String firstId = read.path("messages").get(0).path("id").asText();
        jdbc.update("UPDATE messages SET content = content WHERE id = ?", java.util.UUID.fromString(firstId));
        for (int i = 0; i < 5; i++) {
            JsonNode repeat = call("{\"session_id\":\"" + own.getId() + "\"}");
            assertThat(repeat.path("messages")).isEqualTo(read.path("messages"));
        }
    }

    private JsonNode call(String args) throws Exception {
        return mapper.readTree(tool.execute(args, null, null).content());
    }

    private SessionEntity create(String owner, Instant time) {
        SessionEntity session = new SessionEntity();
        session.setUserId(owner);
        session.setTitle("Test session");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(time);
        session.setUpdatedAt(time);
        session.setLastActive(time);
        return session;
    }
}
