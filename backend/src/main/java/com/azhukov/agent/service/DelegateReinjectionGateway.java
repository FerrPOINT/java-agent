package com.azhukov.agent.service;

import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Hermes gateway consumer loop parity (docs/34-hermes-parity-audit):
 * terminal delegated-task completions are reinjected into the parent
 * session so the next conversation turn sees them.
 *
 * The loop claims each pending completion through
 * {@link DelegatedTaskRunService#claimCompletionDelivery} (single consumer
 * wins atomically), writes a bounded assistant summary into the parent
 * session's message history, queues a steer so an in-flight turn picks the
 * completion up immediately, then acks the claim. Failures release the
 * claim so the existing delivery ledger (attempts cap, drop, stale sweep)
 * governs retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DelegateReinjectionGateway {

    private static final String CONSUMER_ID = "delegate-reinjection-gateway";

    /** Bounded summary: the model reads the full result via action=read. */
    private static final int SUMMARY_MAX_LENGTH = 2000;

    private final ObjectProvider<DelegatedTaskRunService> delegatedTaskRunServiceProvider;
    private final ObjectProvider<SteerBuffer> steerBufferProvider;
    private final ObjectProvider<MessageRepository> messageRepositoryProvider;
    private final ObjectProvider<TransactionTemplate> transactionTemplateProvider;

    private final DelegateCompletionBatcher batcher = new DelegateCompletionBatcher(java.time.Duration.ofSeconds(15));

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void reinjectPendingCompletions() {
        DelegatedTaskRunService runs = delegatedTaskRunServiceProvider.getIfAvailable();
        if (runs == null) {
            return;
        }
        // Bounded pass: claim the tick backlog first, then coalesce per parent
        // session so one session gets a single synthetic completion note
        // instead of N pings (Hermes completion-batch parity).
        java.util.List<DelegatedTaskRunService.DeliveryClaim> claims = new java.util.ArrayList<>();
        while (claims.size() < 20) {
            Optional<DelegatedTaskRunService.DeliveryClaim> claim =
                runs.claimNextPendingDelivery(CONSUMER_ID);
            if (claim.isEmpty()) {
                break;
            }
            claims.add(claim.get());
        }
        if (claims.isEmpty()) {
            return;
        }
        java.util.List<DelegateCompletionBatcher.PendingRun> pending = claims.stream()
            .map(claim -> new DelegateCompletionBatcher.PendingRun(
                claim.runId().toString(),
                claim.run().getParentSessionId(),
                claim.run().getGoal(),
                claim.run().getStatus(),
                claim.run().getCompletedAt()))
            .toList();
        int processed = 0;
        for (java.util.List<DelegateCompletionBatcher.PendingRun> batch : batcher.batches(pending, 5)) {
            java.util.UUID parentSessionId = batch.get(0).parentSessionId();
            String summary = batch.size() == 1
                ? singleRunSummary(claims, batch.get(0))
                : batcher.summary(batch);
            if (summary == null) {
                summary = batcher.summary(batch);
            }
            boolean injected = false;
            String failure = "parent session unavailable";
            try {
                injected = reinjectSummary(parentSessionId, summary);
            } catch (Exception e) {
                log.warn("Delegate reinjection failed for {} run(s) of session {}: {}",
                    batch.size(), parentSessionId, e.getMessage());
                failure = e.getMessage();
            }
            for (DelegateCompletionBatcher.PendingRun run : batch) {
                DelegatedTaskRunService.DeliveryClaim match = claims.stream()
                    .filter(claim -> claim.runId().toString().equals(run.runId()))
                    .findFirst()
                    .orElse(null);
                if (match == null) {
                    continue;
                }
                if (injected) {
                    runs.completeDeliveryClaim(match.runId(), match.claimId(), "parent_session", null);
                    processed++;
                } else {
                    runs.releaseDeliveryClaim(
                        match.runId(), match.claimId(), "parent_session", failure);
                }
            }
        }
        if (processed > 0) {
            log.info("Delegate reinjection gateway delivered {} completion(s) in {} coalesced note(s)",
                processed, batcher.batches(pending, 5).size());
        }
    }

    private String singleRunSummary(java.util.List<DelegatedTaskRunService.DeliveryClaim> claims,
                                    DelegateCompletionBatcher.PendingRun pending) {
        return claims.stream()
            .filter(claim -> claim.runId().toString().equals(pending.runId()))
            .findFirst()
            .map(claim -> summaryForRun(claim.run()))
            .orElse(null);
    }

    /** Hermes-parity detail note for a single delegated completion. */
    private String summaryForRun(DelegatedTaskRunEntity run) {
        String resultDetail = resultDetail(run.getResultJson());
        StringBuilder sb = new StringBuilder("[delegated task completed] " + run.getGoal() + "\n");
        sb.append("run: ").append(run.getId()).append("\n");
        sb.append("status: ").append(run.getStatus()).append("\n");
        if (resultDetail != null && !resultDetail.isBlank()) {
            sb.append(resultDetail).append("\n");
        }
        sb.append("delegate_task action=read session=").append(run.getChildSessionId() != null
            ? run.getChildSessionId() : run.getParentSessionId());
        return sb.toString();
    }

    private String resultDetail(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultJson);
            if (node.has("results") && node.get("results").isArray() && !node.get("results").isEmpty()) {
                return node.get("results").get(0).path("summary").asText(null);
            }
        } catch (Exception ignored) {
            // fall through to null
        }
        return null;
    }

    private boolean reinjectSummary(UUID parentSessionId, String summary) {
        if (parentSessionId == null) {
            return false;
        }
        MessageRepository messages = messageRepositoryProvider.getIfAvailable();
        TransactionTemplate tx = transactionTemplateProvider.getIfAvailable();
        if (messages == null || tx == null) {
            return false;
        }
        tx.executeWithoutResult(status -> {
            MessageEntity note = new MessageEntity();
            note.setSessionId(parentSessionId);
            note.setRole("assistant");
            note.setContent(summary);
            note.setTurnIndex(0);
            note.setCreatedAt(Instant.now());
            messages.save(note);
        });
        SteerBuffer steer = steerBufferProvider.getIfAvailable();
        if (steer != null) {
            steer.steer(parentSessionId, summary);
        }
        return true;
    }

    private String bound(String value, int max) {
        String normalized = value.strip();
        return normalized.length() <= max ? normalized
            : normalized.substring(0, max) + "…[truncated]";
    }
}
