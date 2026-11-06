package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.ReviewSummaryEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.api.AgentChatController;
import com.azhukov.agent.config.AgentProperties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
class ReviewSummaryClaimPostgresTest extends PostgresTestContainer {
    @Autowired ReviewSummaryRepository repository;
    @Autowired SessionRepository sessions;
    @Autowired AgentChatController controller;
    @Autowired AgentProperties properties;
    @Autowired MockMvc mvc;

    @Test
    void httpEndpointClaimsOnceAndKeepsForeignDelivery() throws Exception {
        var session = new SessionEntity();
        session.setUserId("test-owner");
        session.setModelProvider("noop");
        session.setModelName("test");
        session = sessions.saveAndFlush(session);
        var review = new ReviewSummaryEntity();
        review.setSessionId(session.getId());
        review.setDeliveryId(UUID.randomUUID());
        review.setSummary("review");
        repository.saveAndFlush(review);
        boolean wasEnabled = properties.getMemory().getBackgroundReview().isEnabled();
        properties.getMemory().getBackgroundReview().setEnabled(true);
        try {
            String root = "/api/v1/agent/session/" + session.getId() + "/review/";
            mvc.perform(get(root + "pending")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryId").value(review.getDeliveryId().toString()));
            mvc.perform(post(root + "ack").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deliveryId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledged").value(false));
            assertThat(repository.findById(review.getDeliveryId())).isPresent();
            String request = "{\"deliveryId\":\"" + review.getDeliveryId() + "\"}";
            mvc.perform(post(root + "ack").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledged").value(true));
            mvc.perform(post(root + "ack").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acknowledged").value(false));
            mvc.perform(get(root + "pending")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(false));
        } finally {
            properties.getMemory().getBackgroundReview().setEnabled(wasEnabled);
            repository.deleteById(review.getDeliveryId());
            sessions.deleteById(session.getId());
        }
    }

    @Test
    void controllerClaimsConcurrentlyAcrossIndependentRequests() throws Exception {
        var session = new SessionEntity();
        session.setUserId("test-owner");
        session.setModelProvider("noop");
        session.setModelName("test");
        session = sessions.saveAndFlush(session);
        var review = new ReviewSummaryEntity();
        review.setSessionId(session.getId());
        review.setDeliveryId(UUID.randomUUID());
        review.setSummary("review");
        repository.saveAndFlush(review);
        UUID sessionId = session.getId();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("claim start timeout");
                return (boolean) controller.acknowledgePendingReview(sessionId.toString(),
                    java.util.Map.of("deliveryId", review.getDeliveryId().toString())).get("acknowledged");
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(repository.findById(review.getDeliveryId())).isEmpty();
        } finally {
            start.countDown();
            pool.shutdownNow();
            repository.deleteById(review.getDeliveryId());
            sessions.deleteById(sessionId);
        }
    }

}
