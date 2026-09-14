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

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void reinjectPendingCompletions() {
        DelegatedTaskRunService runs = delegatedTaskRunServiceProvider.getIfAvailable();
        if (runs == null) {
            return;
        }
        int processed = 0;
        // Bounded pass: one claim probe per tick keeps the loop cheap and
        // lets the scheduled cadence drain the backlog.
        while (processed < 20) {
            Optional<DelegatedTaskRunService.DeliveryClaim> claim =
                runs.claimNextPendingDelivery(CONSUMER_ID);
            if (claim.isEmpty()) {
                break;
            }
            DelegatedTaskRunService.DeliveryClaim delivery = claim.get();
            try {
                boolean injected = reinject(delivery.run());
                if (injected) {
                    runs.completeDeliveryClaim(
                        delivery.runId(), delivery.claimId(), "parent_session", null);
                    processed++;
                } else {
                    runs.releaseDeliveryClaim(
                        delivery.runId(), delivery.claimId(), "parent_session",
                        "parent session unavailable");
                }
            } catch (Exception e) {
                log.warn("Delegate reinjection failed for run {}: {}",
                    delivery.runId(), e.getMessage());
                runs.releaseDeliveryClaim(
                    delivery.runId(), delivery.claimId(), "parent_session", e.getMessage());
            }
        }
        if (processed > 0) {
            log.info("Delegate reinjection gateway delivered {} completion(s)", processed);
        }
    }

    private boolean reinject(DelegatedTaskRunEntity run) {
        UUID parentSessionId = run.getParentSessionId();
        if (parentSessionId == null) {
            return false;
        }
        MessageRepository messages = messageRepositoryProvider.getIfAvailable();
        TransactionTemplate tx = transactionTemplateProvider.getIfAvailable();
        if (messages == null || tx == null) {
            return false;
        }
        String summary = summary(run);
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

    private String summary(DelegatedTaskRunEntity run) {
        String status = run.getStatus() == null ? "completed" : run.getStatus();
        String goal = run.getGoal() == null ? "" : run.getGoal();
        String result = run.getResultJson() == null ? "" : run.getResultJson();
        String error = run.getError() == null ? "" : run.getError();
        StringBuilder sb = new StringBuilder("[delegated task completed]")
            .append("\nrun_id: ").append(run.getId())
            .append("\ngoal: ").append(bound(goal, 400))
            .append("\nstatus: ").append(status);
        if (!error.isBlank()) {
            sb.append("\nerror: ").append(bound(error, 400));
        }
        if (!result.isBlank()) {
            sb.append("\nresult: ").append(bound(result, SUMMARY_MAX_LENGTH));
        }
        sb.append("\n(inspect with delegate_task action=read)");
        return sb.toString();
    }

    private String bound(String value, int max) {
        String normalized = value.strip();
        return normalized.length() <= max ? normalized
            : normalized.substring(0, max) + "…[truncated]";
    }
}
