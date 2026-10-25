package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.BackgroundJobEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;


import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class BackgroundJobRepositoryTest extends PostgresTestContainer {

    @Autowired
    private BackgroundJobRepository backgroundJobRepository;

    @Test
    void ownerScopedLookupDoesNotExposeSessionlessJobToAnotherUser() {
        BackgroundJobEntity job = new BackgroundJobEntity();
        job.setUserId("alice");
        job.setPrompt("private task");
        job.setStatus("DONE");
        job.setResult("private result");
        BackgroundJobEntity saved = backgroundJobRepository.saveAndFlush(job);

        assertThat(backgroundJobRepository.findByIdAndUserId(saved.getId(), "alice"))
            .contains(saved);
        assertThat(backgroundJobRepository.findByIdAndUserId(saved.getId(), "bob"))
            .isEmpty();
    }
}
