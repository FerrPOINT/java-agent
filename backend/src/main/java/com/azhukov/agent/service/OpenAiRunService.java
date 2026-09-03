package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.RunControlScope;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.ApiErrorTextRedactor;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.azhukov.agent.tools.terminal.ProcessTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class OpenAiRunService {

    private static final long RUN_STATUS_TTL_SECONDS = 3600;
    private static final long RUN_EVENT_STREAM_TIMEOUT_MS = 600_000L;

    private final AgentRuntimeService agentRuntimeService;
    private final OpenAiSessionService openAiSessionService;
    private final SteerBuffer steerBuffer;
    private final InterruptToken interruptToken;
    private final ApprovalQueue approvalQueue;
    private final Redactor redactor;
    private final ObjectMapper objectMapper;
    private final ProcessTool processTool;

    private final ConcurrentMap<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("openai-runs-", 0).factory());

    public RunRecord submit(OpenAiSessionContext sessionContext,
                            String userMessage,
                            String requestedModel,
                            ModelRequestOptions options,
                            String instructions,
                            List<Message> historyToPersist) {
        return submit(sessionContext, Message.user(userMessage), requestedModel, options, instructions, historyToPersist, null);
    }

    public RunRecord submit(OpenAiSessionContext sessionContext,
                            Message userMessage,
                            String requestedModel,
                            ModelRequestOptions options,
                            String instructions,
                            List<Message> historyToPersist) {
        return submit(sessionContext, userMessage, requestedModel, options, instructions, historyToPersist, null);
    }

    public RunRecord submit(OpenAiSessionContext sessionContext,
                            Message userMessage,
                            String requestedModel,
                            ModelRequestOptions options,
                            String instructions,
                            List<Message> historyToPersist,
                            ApiRunAdmissionService.Reservation reservation) {
        sweepTerminalRuns();
        UUID sessionId = sessionContext.session().id();
        if (historyToPersist != null && !historyToPersist.isEmpty()) {
            openAiSessionService.persistHistory(sessionId, historyToPersist);
        }

        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        UUID controlSessionId = UUID.randomUUID();
        RunRecord record = new RunRecord(runId, sessionId, controlSessionId, requestedModel);
        runs.put(runId, record);

        Session runSession = RunControlScope.withControlSessionId(sessionContext.session(), controlSessionId);
        if (instructions != null && !instructions.isBlank()) {
            runSession = runSession.withMetadata("system_prompt_override", instructions);
        }
        Session effectiveSession = runSession;
        try {
            executor.submit(() -> run(record, effectiveSession, userMessage, options, reservation));
            executor.submit(() -> monitorApprovals(record));
            return record;
        } catch (RuntimeException e) {
            closeReservation(reservation);
            throw e;
        }
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
        record.stopRequested.set(true);
        interruptToken.cancel(record.controlSessionId());
        if (processTool != null) {
            processTool.killOwnedBy(record.controlSessionId());
        }
        record.setStatus("stopping", Map.of("last_event", "run.stopping"));
        record.emit("run.stopping", Map.of());
        return ControlResult.ok(Map.of("run_id", runId, "status", "stopping"));
    }

    public ControlResult steer(String runId, String text) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return ControlResult.notFound();
        }
        String status = record.status();
        if (!"running".equals(status)) {
            return ControlResult.conflict(
                "Run is not currently accepting steer input: " + runId,
                "run_not_accepting_steer");
        }
        if (text == null || text.isBlank()) {
            return ControlResult.badRequest(
                "Missing non-empty steer text; expected 'input', 'message', or 'text'.",
                "invalid_steer_input");
        }
        boolean accepted = steerBuffer.steer(record.controlSessionId(), text);
        if (!accepted) {
            return ControlResult.conflict("Run did not accept steer text: " + runId, "steer_not_accepted");
        }
        record.setStatus(status, Map.of("last_event", "run.steered"));
        record.emit("run.steered", Map.of("accepted", true));
        return ControlResult.ok(Map.of("object", "hermes.run.steer", "run_id", runId, "accepted", true));
    }

    public ControlResult approval(String runId, String rawChoice, boolean resolveAll) {
        RunRecord record = runs.get(runId);
        if (record == null) {
            return ControlResult.notFound();
        }
        String choice = normalizeApprovalChoice(rawChoice);
        if (choice == null) {
            return ControlResult.badRequest(
                "Invalid approval choice; expected one of: once, session, always, deny",
                "invalid_approval_choice");
        }
        if (record.isTerminal()) {
            return ControlResult.conflict(
                "Run has no active approval session: " + runId,
                "approval_not_active");
        }
        ApprovalQueue.PendingApproval pending = approvalQueue.getPending(record.controlSessionId());
        if (pending == null || pending.approved() || pending.denied() || pending.superseded()) {
            return ControlResult.conflict("Run has no pending approval: " + runId, "approval_not_pending");
        }

        if ("deny".equals(choice)) {
            approvalQueue.deny(record.controlSessionId(), null);
        } else {
            approvalQueue.approve(record.controlSessionId(), "approve", null);
        }
        int resolved = resolveAll ? 1 : 1;
        record.setStatus("running", Map.of("last_event", "approval.responded"));
        record.emit("approval.responded", Map.of("choice", choice, "resolved", resolved));
        return ControlResult.ok(Map.of(
            "object", "hermes.run.approval_response",
            "run_id", runId,
            "choice", choice,
            "resolved", resolved
        ));
    }

    public SseEmitter events(String runId) {
        RunRecord record = runs.get(runId);
        if (record == null || !record.claimEventStream()) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(RUN_EVENT_STREAM_TIMEOUT_MS);
        executor.submit(() -> drainEvents(record, emitter));
        return emitter;
    }

    private void run(RunRecord record,
                     Session session,
                     Message userMessage,
                     ModelRequestOptions options,
                     ApiRunAdmissionService.Reservation reservation) {
        record.setStatus("running", Map.of("last_event", "run.started"));
        record.emit("run.started", Map.of());
        try {
            if (record.stopRequested.get() || interruptToken.isCancelled(record.controlSessionId())) {
                record.setStatus("cancelled", Map.of("last_event", "run.cancelled"));
                record.emit("run.cancelled", Map.of());
                return;
            }
            ChatResponseDto response = userMessage != null
                    && userMessage.imageCount() != null
                    && userMessage.imageCount() > 0
                ? agentRuntimeService.runApiTurn(session, userMessage, options)
                : agentRuntimeService.runApiTurn(session, userMessage != null ? userMessage.content() : "", options);
            if (record.stopRequested.get() || interruptToken.isCancelled(record.controlSessionId())) {
                record.setStatus("cancelled", Map.of("last_event", "run.cancelled"));
                record.emit("run.cancelled", Map.of());
                return;
            }
            if (response.completed()) {
                Map<String, Object> usage = usage(response);
                Map<String, Object> fields = new LinkedHashMap<>();
                String pendingSteer = steerBuffer.consume(record.controlSessionId());
                String output = response.content() != null ? response.content() : "";
                fields.put("output", output);
                fields.put("usage", usage);
                fields.put("last_event", "run.completed");
                if (pendingSteer != null && !pendingSteer.isBlank()) {
                    fields.put("pending_steer", pendingSteer);
                }
                record.setStatus("completed", fields);
                if (!output.isEmpty()) {
                    record.emit("message.delta", Map.of("delta", output));
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("output", output);
                event.put("usage", usage);
                if (pendingSteer != null && !pendingSteer.isBlank()) {
                    event.put("pending_steer", pendingSteer);
                }
                record.emit("run.completed", event);
            } else {
                String output = response.content() != null ? response.content() : "agent run did not complete";
                String safeOutput = apiErrorText(output);
                record.setStatus("failed", Map.of("error", safeOutput, "last_event", "run.failed"));
                record.emit("run.failed", Map.of("error", safeOutput));
            }
        } catch (Exception e) {
            if (record.stopRequested.get()) {
                record.setStatus("cancelled", Map.of("last_event", "run.cancelled"));
                record.emit("run.cancelled", Map.of());
            } else {
                String message = apiErrorText(e.getMessage() != null ? e.getMessage() : e.toString());
                record.setStatus("failed", Map.of("error", message, "last_event", "run.failed"));
                record.emit("run.failed", Map.of("error", message));
            }
        } finally {
            approvalQueue.clear(record.controlSessionId());
            steerBuffer.clear(record.controlSessionId());
            interruptToken.remove(record.controlSessionId());
            closeReservation(reservation);
            record.closeEvents();
        }
    }

    private static void closeReservation(ApiRunAdmissionService.Reservation reservation) {
        if (reservation != null) {
            reservation.close();
        }
    }

    private void monitorApprovals(RunRecord record) {
        UUID lastApprovalId = null;
        try {
            while (!record.isTerminal()) {
                ApprovalQueue.PendingApproval pending = approvalQueue.getPending(record.controlSessionId());
                if (pending != null && !pending.approved() && !pending.denied() && !pending.superseded()) {
                    if (!pending.requestId().equals(lastApprovalId)) {
                        lastApprovalId = pending.requestId();
                        record.setStatus("waiting_for_approval", Map.of("last_event", "approval.request"));
                        record.emit("approval.request", approvalPayload(pending));
                    }
                } else if ("waiting_for_approval".equals(record.status())) {
                    record.setStatus("running", Map.of("last_event", "approval.cleared"));
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
        payload.put("choices", List.of("once", "session", "always", "deny"));
        if (approval.call() != null) {
            payload.put("tool", approval.call().name());
            payload.put("arguments", apiErrorText(approval.call().arguments()));
        }
        if (approval.expiresAt() != null) {
            payload.put("expires_at", approval.expiresAt().toString());
        }
        return payload;
    }

    private void drainEvents(RunRecord record, SseEmitter emitter) {
        try {
            while (true) {
                QueuedEvent event = record.events.poll(30, TimeUnit.SECONDS);
                if (event == null) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    continue;
                }
                if (event.terminal()) {
                    emitter.send(SseEmitter.event().comment("stream closed"));
                    emitter.complete();
                    return;
                }
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event.payload())));
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
        } catch (Exception e) {
            emitter.completeWithError(e);
        } finally {
            record.closeEventStream();
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
        return switch (value) {
            case "once", "session", "always", "deny" -> value;
            default -> null;
        };
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

    public static final class RunRecord {
        private final String runId;
        private final UUID sessionId;
        private final UUID controlSessionId;
        private final String model;
        private final double createdAt;
        private final BlockingQueue<QueuedEvent> events = new LinkedBlockingQueue<>();
        private final AtomicBoolean eventStreamClaimed = new AtomicBoolean(false);
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private String status = "queued";
        private double updatedAt;

        private RunRecord(String runId, UUID sessionId, UUID controlSessionId, String model) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.controlSessionId = controlSessionId;
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

        public synchronized String status() {
            return status;
        }

        public synchronized double updatedAt() {
            return updatedAt;
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
            payload.putAll(extra);
            return payload;
        }

        private synchronized void setStatus(String status, Map<String, Object> fields) {
            this.status = status;
            this.updatedAt = epochSeconds();
            if (fields != null) {
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (entry.getValue() != null) {
                        extra.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        private void emit(String event, Map<String, Object> fields) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event);
            payload.put("run_id", runId);
            payload.put("timestamp", epochSeconds());
            if (fields != null) {
                payload.putAll(fields);
            }
            events.offer(new QueuedEvent(payload, false));
        }

        private void closeEvents() {
            events.offer(new QueuedEvent(Map.of(), true));
        }

        private boolean claimEventStream() {
            return eventStreamClaimed.compareAndSet(false, true);
        }

        private void closeEventStream() {
            eventStreamClaimed.set(true);
        }

        private synchronized boolean isTerminal() {
            return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
        }
    }

    private record QueuedEvent(Map<String, Object> payload, boolean terminal) {}

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
