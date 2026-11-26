package com.azhukov.agent.service;

import com.azhukov.agent.shared.dto.ChatResponseDto;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.RunControlScope;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.ApiErrorTextRedactor;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.azhukov.agent.tools.terminal.ProcessTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Durable OpenAI Runs API surface (WP-6): run state machine persistence and
 * restart-safe event replay. A run's events survive backend restarts and
 * replay strictly after the client cursor — no duplicates, no gaps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiRunService {

    private static final long RUN_STATUS_TTL_SECONDS = 3600;
    private static final long RUN_EVENT_STREAM_TIMEOUT_MS = 600_000L;
    private static final int EVENT_BATCH_SIZE = 64;
    private static final long EVENT_POLL_MS = 500L;
    private static final long EVENT_KEEPALIVE_MS = 15_000L;
    private static final List<String> APPROVAL_CHOICES = List.of("once", "deny");

    private final AgentRuntimeService agentRuntimeService;
    private final OpenAiSessionService openAiSessionService;
    private final SteerBuffer steerBuffer;
    private final InterruptToken interruptToken;
    private final ApprovalQueue approvalQueue;
    private final Redactor redactor;
    private final ObjectMapper objectMapper;
    private final ProcessTool processTool;

    private final org.springframework.beans.factory.ObjectProvider<OpenAiRunStateMachine> runStateMachineProvider;

    private final ConcurrentMap<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("openai-runs-", 0).factory());

    private com.azhukov.agent.core.agent.SessionTurnLockManager turnLocks =
        new com.azhukov.agent.core.agent.SessionTurnLockManager();

    @org.springframework.beans.factory.annotation.Autowired
    void setTurnLocks(com.azhukov.agent.core.agent.SessionTurnLockManager turnLocks) {
        this.turnLocks = turnLocks;
    }

    public record SubmissionKey(String runId, String profile, String fingerprint) {}

    public record Acceptance(String runId, String sessionId) {}

    public static final class IdempotencyConflict extends RuntimeException {}

    public static final class DurableAcceptanceUnavailable extends RuntimeException {}

    /** Read a previously committed acceptance without opening a session or dispatching again. */
    public Optional<Acceptance> replay(SubmissionKey key) {
        OpenAiRunStateMachine machine = stateMachine();
        if (machine == null) {
            throw new DurableAcceptanceUnavailable();
        }
        return machine.state(key.runId()).map(state -> {
            String scopedUser = UserContext.scopeUserId();
            if (!Objects.equals(state.getRequestFingerprint(), key.fingerprint())
                || !key.profile().equals(state.getProfile())
                || (scopedUser != null && !scopedUser.equals(state.getUserId()))
                || state.getAcceptedSessionId() == null) {
                throw new IdempotencyConflict();
            }
            return new Acceptance(state.getRunId(), state.getAcceptedSessionId());
        });
    }

    public Acceptance submit(Supplier<OpenAiSessionContext> resolveSession,
                             Message userMessage,
                             String requestedModel,
                             ModelRequestOptions options,
                             String instructions,
                             List<Message> historyToPersist,
                             UUID previousSessionId,
                             ApiRunAdmissionService.Reservation reservation,
                             SubmissionKey key) {
        sweepTerminalRuns();
        String runId = key == null ? "run_" + UUID.randomUUID().toString().replace("-", "") : key.runId();
        OpenAiRunStateMachine machine = stateMachine();
        OpenAiSessionContext context;
        try {
            if (machine == null) {
                if (key != null) {
                    throw new DurableAcceptanceUnavailable();
                }
                context = resolveSession.get();
            } else {
                context = machine.accept(runId, requestedModel, key == null ? null : key.fingerprint(), resolveSession);
            }
        } catch (OpenAiRunStateMachine.AlreadyAccepted e) {
            closeReservation(reservation);
            if (key == null) {
                throw e;
            }
            return replay(key).orElseThrow(DurableAcceptanceUnavailable::new);
        } catch (RuntimeException e) {
            closeReservation(reservation);
            throw e;
        }

        Session session = context.session();
        UUID controlSessionId = UUID.randomUUID();
        RunRecord record = new RunRecord(runId, session.id(), controlSessionId, session.userId(),
            session.profile(), requestedModel);
        record.stateMachine = machine;
        runs.put(runId, record);
        Session runSession = RunControlScope.withControlSessionId(session, controlSessionId);
        if (instructions != null && !instructions.isBlank()) {
            runSession = runSession.withMetadata("system_prompt_override", instructions);
        }
        Session effectiveSession = runSession;
        List<Message> history = session.id().equals(previousSessionId) ? List.of() : historyToPersist;
        try {
            executor.submit(UserContext.wrap(() -> run(record, effectiveSession, userMessage, options, history, reservation)));
        } catch (RejectedExecutionException e) {
            closeReservation(reservation);
            record.finishFailed("Run execution could not start");
            return new Acceptance(runId, context.responseSessionId());
        }
        try {
            executor.submit(() -> monitorApprovals(record));
        } catch (RejectedExecutionException e) {
            log.debug("Run approval monitor is stopping for {}", runId);
        }
        return new Acceptance(runId, context.responseSessionId());
    }

    public RunRecord get(String runId) {
        return runs.get(runId);
    }

    public int activeRunCount() {
        int count = 0;
        for (RunRecord record : runs.values()) {
            if (!record.isTerminal()) {
                count++;
            }
        }
        return count;
    }

    public ControlResult stop(String runId) {
        RunRecord record = runs.get(runId);
        if (record == null || record.isTerminal()) {
            return ControlResult.notFound();
        }
        boolean signalProcesses = false;
        try {
            synchronized (record) {
                if (record.isTerminal()) return ControlResult.notFound();
                if (record.stopRequested.compareAndSet(false, true)) {
                    signalProcesses = true;
                    interruptToken.cancel(record.controlSessionId());
                }
                if (!"stopping".equals(record.status())) {
                    record.publish("stopping", "run.stopping", Map.of());
                }
            }
        } finally {
            if (signalProcesses && processTool != null) {
                processTool.killOwnedBy(record.controlSessionId());
            }
        }
        return ControlResult.ok(Map.of("run_id", runId, "status", "stopping"));
    }

    public ControlResult steer(String runId, String text) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return ControlResult.notFound();
        }
        synchronized (record) {
            if (!"running".equals(record.status()) || record.stopRequested.get()) {
                return ControlResult.conflict(
                    "Run is not currently accepting steer input: " + runId,
                    "run_not_accepting_steer");
            }
            if (text == null || text.isBlank()) {
                return ControlResult.badRequest(
                    "Missing non-empty steer text; expected 'input', 'message', or 'text'.",
                    "invalid_steer_input");
            }
            record.publish(null, "run.steered", Map.of("accepted", true));
            steerBuffer.steer(record.controlSessionId(), text);
        }
        return ControlResult.ok(Map.of("object", "hermes.run.steer", "run_id", runId, "accepted", true));
    }

    public ControlResult approval(String runId, String rawChoice, boolean resolveAll) {
        return approval(runId, rawChoice, resolveAll, null);
    }

    /**
     * Hermes {@code _handle_run_approval} parity: resolves a pending run
     * approval. {@code requestId} optionally targets one exact pending request
     * (its UUID must match, else 409 approval_not_pending — never a silent
     * resolve of a different request); {@code resolveAll} resolves every
     * pending request for the run's control session. The queue holds at most
     * one request and the decision only resolves that request.
     */
    public ControlResult approval(String runId, String rawChoice, boolean resolveAll, String requestId) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return ControlResult.notFound();
        }
        String choice = normalizeApprovalChoice(rawChoice);
        if (choice == null) {
            return ControlResult.badRequest(
                "Invalid approval choice; expected " + String.join(" or ", APPROVAL_CHOICES),
                "invalid_approval_choice");
        }
        if (requestId != null && (requestId.isBlank() || requestId.length() > 256)) {
            return ControlResult.badRequest("Approval request_id is invalid.", "invalid_approval_request");
        }
        synchronized (record) {
            if (record.isTerminal() || record.stopRequested.get()) {
                return ControlResult.conflict(
                    "Run has no active approval session: " + runId, "approval_not_active");
            }
            ApprovalQueue.PendingApproval pending = approvalQueue.getPending(record.controlSessionId());
            if (pending == null || (requestId != null && !requestId.equals(pending.requestId().toString()))) {
                return ControlResult.conflict("Run has no matching pending approval: " + runId, "approval_not_pending");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("choice", choice);
            if (requestId != null) payload.put("request_id", requestId);
            // Persist the response before releasing the waiter. The approval monitor
            // publishes the status change once the request is no longer pending.
            if (!approvalQueue.resolvePending(pending, !"deny".equals(choice),
                    () -> record.publish(null, "approval.responded", payload))) {
                return ControlResult.conflict("Run has no pending approval: " + runId, "approval_not_pending");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("object", "hermes.run.approval_response");
        body.put("run_id", runId);
        body.put("choice", choice);
        if (requestId != null) {
            body.put("request_id", requestId);
        }
        body.put("resolved", 1);
        return ControlResult.ok(body);
    }

    /** Stream ordered committed events after the client's last observed sequence. */
    public SseEmitter events(String runId, long after) {
        var machine = stateMachine();
        if (machine == null) {
            throw new DurableAcceptanceUnavailable();
        }
        SseEmitter emitter = new SseEmitter(RUN_EVENT_STREAM_TIMEOUT_MS);
        var worker = executor.submit(UserContext.wrap(() -> streamEvents(machine, runId, after, emitter)));
        emitter.onTimeout(() -> worker.cancel(true));
        emitter.onError(error -> worker.cancel(true));
        emitter.onCompletion(() -> worker.cancel(true));
        return emitter;
    }

    private void run(RunRecord record,
                     Session session,
                     Message userMessage,
                     ModelRequestOptions options,
                     List<Message> historyToPersist,
                     ApiRunAdmissionService.Reservation reservation) {
        try {
            UserContext.requireCurrentAccess();
            if (record.stopRequested.get() || interruptToken.isCancelled(record.controlSessionId())) {
                record.publish("cancelled", "run.cancelled", Map.of());
                return;
            }
            ChatResponseDto response = turnLocks.withHistoryTurn(session.id(), () -> {
                if (record.stopRequested.get()) throw new IllegalStateException("Run stopped");
                record.publish("running", "run.started", Map.of());
                if (historyToPersist != null && !historyToPersist.isEmpty()) {
                    openAiSessionService.persistHistory(session.id(), historyToPersist);
                }
                return userMessage != null
                        && userMessage.imageCount() != null
                        && userMessage.imageCount() > 0
                    ? agentRuntimeService.runApiTurn(session, userMessage, options)
                    : agentRuntimeService.runApiTurn(session, userMessage != null ? userMessage.content() : "", options);
            });
            if (record.stopRequested.get() || interruptToken.isCancelled(record.controlSessionId())) {
                record.publish("cancelled", "run.cancelled", Map.of());
                return;
            }
            if (response.completed()) {
                Map<String, Object> usage = usage(response);
                String output = response.content() != null ? response.content() : "";
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("output", output);
                event.put("usage", usage);
                synchronized (record) {
                    String pendingSteer = steerBuffer.consume(record.controlSessionId());
                    if (pendingSteer != null && !pendingSteer.isBlank()) event.put("pending_steer", pendingSteer);
                    if (record.stopRequested.get()) {
                        record.publish("cancelled", "run.cancelled", Map.of());
                    } else {
                        if (!output.isEmpty()) record.publish(null, "message.delta", Map.of("delta", output));
                        record.publish("completed", "run.completed", event);
                    }
                }
            } else {
                String output = response.content() != null ? response.content() : "agent run did not complete";
                String safeOutput = apiErrorText(output);
                record.finishFailed(safeOutput);
            }
        } catch (Exception e) {
            String message = apiErrorText(e.getMessage() != null ? e.getMessage() : e.toString());
            try {
                record.finishFailed(message);
            } catch (RuntimeException storageFailure) {
                log.error("Unable to record outcome for run {}", record.runId(), storageFailure);
                record.executionAvailable = false;
            }
        } finally {
            approvalQueue.clear(record.controlSessionId());
            steerBuffer.clear(record.controlSessionId());
            interruptToken.remove(record.controlSessionId());
            closeReservation(reservation);
        }
    }

    private static void closeReservation(ApiRunAdmissionService.Reservation reservation) {
        if (reservation != null) {
            reservation.close();
        }
    }

    private void monitorApprovals(RunRecord record) {
        UUID lastApprovalId = null;
        boolean writeFailed = false;
        try {
            while (!record.isTerminal()) {
                try {
                    synchronized (record) {
                        if (record.isTerminal() || record.stopRequested.get()) return;
                        ApprovalQueue.PendingApproval pending = approvalQueue.getPending(record.controlSessionId());
                        if (pending != null && !pending.approved() && !pending.denied()) {
                            if (!pending.requestId().equals(lastApprovalId)) {
                                record.publish("waiting_for_approval", "approval.request", approvalPayload(pending));
                                lastApprovalId = pending.requestId();
                            }
                        } else if ("waiting_for_approval".equals(record.status())) {
                            record.publish("running", "approval.cleared", Map.of());
                        }
                    }
                    writeFailed = false;
                } catch (TransientDataAccessException | DataAccessResourceFailureException
                        | CannotCreateTransactionException e) {
                    if (!writeFailed) {
                        log.warn("Run {} approval update failed; will retry on the next poll", record.runId(), e);
                    }
                    writeFailed = true;
                }
                TimeUnit.MILLISECONDS.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> approvalPayload(ApprovalQueue.PendingApproval approval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", approval.requestId().toString());
        payload.put("reason", apiErrorText(approval.reason()));
        payload.put("choices", APPROVAL_CHOICES);
        if (approval.call() != null) {
            payload.put("tool", approval.call().name());
            payload.put("arguments", apiErrorText(approval.call().arguments()));
        }
        if (approval.expiresAt() != null) {
            payload.put("expires_at", approval.expiresAt().toString());
        }
        return payload;
    }

    private void streamEvents(OpenAiRunStateMachine machine, String runId, long cursor, SseEmitter emitter) {
        long lastWrite = System.nanoTime();
        try {
            while (true) {
                UserContext.requireCurrentAccess();
                var state = machine.state(runId).orElseThrow();
                var events = machine.replayParsed(runId, cursor, EVENT_BATCH_SIZE);
                if (events.isEmpty() && cursor < state.getLastSeq()) {
                    throw new IllegalStateException("Run event history is incomplete");
                }
                for (var event : events) {
                    UserContext.requireCurrentAccess();
                    if (event.seq() != cursor + 1) {
                        throw new IllegalStateException("Run event history is not contiguous");
                    }
                    emitter.send(SseEmitter.event().id(Long.toString(event.seq()))
                        .data(objectMapper.writeValueAsString(event.payload())));
                    cursor = event.seq();
                    lastWrite = System.nanoTime();
                }
                if (OpenAiRunStateMachine.isTerminal(state.getState()) && cursor >= state.getLastSeq()) {
                    emitter.complete();
                    return;
                }
                if (events.size() == EVENT_BATCH_SIZE) {
                    continue;
                }
                var record = runs.get(runId);
                if (record != null && !record.executionAvailable) {
                    throw new IllegalStateException("Run outcome is unavailable");
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastWrite) >= EVENT_KEEPALIVE_MS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastWrite = System.nanoTime();
                }
                Thread.sleep(UserContext.accessWaitMillis(EVENT_POLL_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> usage(ChatResponseDto response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", response.contextTokens() != null ? response.contextTokens() : 0);
        usage.put("output_tokens", 0);
        usage.put("total_tokens", response.contextTokens() != null ? response.contextTokens() : 0);
        return usage;
    }

    private String apiErrorText(String value) {
        return ApiErrorTextRedactor.redacted(value, redactor);
    }

    private String normalizeApprovalChoice(String rawChoice) {
        String value = rawChoice == null ? "" : rawChoice.trim().toLowerCase(Locale.ROOT);
        value = switch (value) {
            case "approve", "approved", "allow" -> "once";
            default -> value;
        };
        return APPROVAL_CHOICES.contains(value) ? value : null;
    }

    private void sweepTerminalRuns() {
        double cutoff = epochSeconds() - RUN_STATUS_TTL_SECONDS;
        runs.entrySet().removeIf(entry -> entry.getValue().isTerminal()
            && entry.getValue().updatedAt() < cutoff);
    }

    private static double epochSeconds() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ── WP-6 durable state machine mirror ─────────────────────────────

    private OpenAiRunStateMachine stateMachine() {
        return runStateMachineProvider == null ? null : runStateMachineProvider.getIfAvailable();
    }

    public static RunRecord restoredRun(String runId, UUID sessionId, String userId, String profile, String model,
                                        String status, java.time.Instant createdAt, java.time.Instant updatedAt,
                                        Map<String, Object> lastEvent) {
        RunRecord run = new RunRecord(runId, sessionId, sessionId, userId, profile, model);
        run.restoreStatus(status, createdAt, updatedAt);
        run.executionAvailable = false;
        for (String field : List.of("output", "usage", "error", "pending_steer")) {
            if (lastEvent.containsKey(field)) run.extra.put(field, lastEvent.get(field));
        }
        if (lastEvent.containsKey("event")) run.extra.put("last_event", lastEvent.get("event"));
        return run;
    }

    public static final class RunRecord {
        private final String runId;
        private final UUID sessionId;
        private final UUID controlSessionId;
        private final String userId;
        private final String profile;
        private final String model;
        private double createdAt;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private OpenAiRunStateMachine stateMachine;
        private volatile boolean executionAvailable = true;
        private String status = "queued";
        private double updatedAt;

        private RunRecord(String runId, UUID sessionId, UUID controlSessionId, String userId, String profile, String model) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.controlSessionId = controlSessionId;
            this.userId = userId;
            this.profile = profile;
            this.model = model;
            this.createdAt = epochSeconds();
            this.updatedAt = this.createdAt;
        }

        public String runId() {
            return runId;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public UUID controlSessionId() {
            return controlSessionId;
        }

        public String userId() {
            return userId;
        }

        public String profile() {
            return profile;
        }

        public synchronized String status() {
            return status;
        }

        public synchronized double updatedAt() {
            return updatedAt;
        }

        private synchronized void restoreStatus(String status, java.time.Instant createdAt, java.time.Instant updatedAt) {
            this.status = status;
            if (createdAt != null) {
                this.createdAt = createdAt.toEpochMilli() / 1000.0;
            }
            if (updatedAt != null) {
                this.updatedAt = updatedAt.toEpochMilli() / 1000.0;
            }
        }

        public synchronized Map<String, Object> snapshot() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("object", "hermes.run");
            payload.put("run_id", runId);
            payload.put("status", status);
            payload.put("updated_at", updatedAt);
            payload.put("created_at", createdAt);
            payload.put("session_id", sessionId.toString());
            payload.put("model", model);
            if (!executionAvailable) payload.put("execution_available", false);
            payload.putAll(extra);
            return payload;
        }

        private synchronized void finishFailed(String message) {
            if (stopRequested.get()) publish("cancelled", "run.cancelled", Map.of());
            else publish("failed", "run.failed", Map.of("error", message));
        }

        private synchronized void publish(String nextStatus, String event, Map<String, Object> fields) {
            if (isTerminal() || ("stopping".equals(status) && !"cancelled".equals(nextStatus))) return;
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            payload.put("event", event);
            payload.put("run_id", runId);
            payload.put("timestamp", epochSeconds());
            if (stateMachine != null) {
                String durableState = switch (nextStatus == null ? "" : nextStatus) {
                    case "running" -> OpenAiRunStateMachine.IN_PROGRESS;
                    case "waiting_for_approval" -> OpenAiRunStateMachine.REQUIRES_ACTION;
                    case "completed", "failed", "cancelled" -> nextStatus;
                    default -> null;
                };
                stateMachine.recordEvent(runId, durableState, payload);
            }
            if (nextStatus != null) status = nextStatus;
            updatedAt = epochSeconds();
            extra.put("last_event", event);
            for (String key : List.of("output", "usage", "pending_steer", "error")) {
                if (fields.containsKey(key)) extra.put(key, fields.get(key));
            }
        }

        private synchronized boolean isTerminal() {
            return !executionAvailable || "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        }
    }

    public record ControlResult(int status, String message, String code, Map<String, Object> body) {
        private static ControlResult ok(Map<String, Object> body) {
            return new ControlResult(200, null, null, body);
        }

        private static ControlResult notFound() {
            return new ControlResult(404, "Run not found", "run_not_found", null);
        }

        private static ControlResult badRequest(String message, String code) {
            return new ControlResult(400, message, code, null);
        }

        private static ControlResult conflict(String message, String code) {
            return new ControlResult(409, message, code, null);
        }
    }
}
