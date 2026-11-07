package com.azhukov.agent.service;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.support.NoopSpringIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CronSuggestionNoopTest extends NoopSpringIntegrationTest {

    @Autowired
    private CronSuggestionService suggestions;

    @Test
    void concurrentCatalogSeedsCommitOneBoundedSetInH2() throws Exception {
        String owner = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> seed(owner, start));
            var second = workers.submit(() -> seed(owner, start));
            start.countDown();

            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(4);
        }

        UserContext.set(owner, UserContext.ROLE_USER);
        try {
            assertThat(suggestions.listPending()).hasSize(4);
            assertThat(suggestions.seedCatalogSuggestions()).isZero();
            var job = new CronSuggestionService.JobSpec("extra", "every 1h", "prompt", "local", null);
            assertThat(suggestions.addSuggestion("fifth", "", "test", job, "fifth")).isNotNull();
            assertThat(suggestions.addSuggestion("sixth", "", "test", job, "sixth")).isNull();
            assertThat(suggestions.listPending()).hasSize(5);
        } finally {
            UserContext.clear();
        }
    }

    private int seed(String owner, CountDownLatch start) throws InterruptedException {
        UserContext.set(owner, UserContext.ROLE_USER);
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Workers did not start");
            return suggestions.seedCatalogSuggestions();
        } finally {
            UserContext.clear();
        }
    }
}
