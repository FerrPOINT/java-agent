package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.CronSuggestionEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("slow")
@Transactional
class CronSuggestionRepositoryTest extends PostgresTestContainer {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private CronSuggestionRepository cronSuggestionRepository;

    @Test
    void ownerScopedQueriesDoNotLeakOrReuseAnotherUsersDedupKey() {
        CronSuggestionEntity alice = cronSuggestionRepository.save(newSuggestion("alice", "daily-briefing"));
        CronSuggestionEntity bob = cronSuggestionRepository.save(newSuggestion("bob", "daily-briefing"));

        assertThat(cronSuggestionRepository.findByUserIdAndStatusOrderByCreatedAtAsc("alice", "pending"))
            .extracting(CronSuggestionEntity::getId)
            .containsExactly(alice.getId());
        assertThat(cronSuggestionRepository.findByIdAndUserId(bob.getId(), "alice")).isEmpty();
        assertThat(cronSuggestionRepository.findByUserIdAndDedupKey("alice", "daily-briefing"))
            .contains(alice);
        assertThat(cronSuggestionRepository.findByUserIdAndDedupKey("bob", "daily-briefing"))
            .contains(bob);
    }

    @Test
    void sameOwnerCannotPersistTheSameDedupKeyTwice() {
        cronSuggestionRepository.saveAndFlush(newSuggestion("alice", "daily-briefing"));

        assertThatThrownBy(() -> cronSuggestionRepository.saveAndFlush(newSuggestion("alice", "daily-briefing")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static CronSuggestionEntity newSuggestion(String userId, String dedupKey) {
        CronSuggestionEntity entity = new CronSuggestionEntity();
        entity.setUserId(userId);
        entity.setTitle("Daily briefing");
        entity.setDescription("description");
        entity.setSource("catalog");
        entity.setJobName("daily-briefing");
        entity.setJobSchedule("0 8 * * *");
        entity.setJobPrompt("prompt");
        entity.setDeliverTo("local");
        entity.setDedupKey(dedupKey);
        entity.setStatus("pending");
        entity.setCreatedAt(CREATED_AT);
        return entity;
    }
}
