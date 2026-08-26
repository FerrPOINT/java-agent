package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.StreamEvent;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.StreamContext;
import com.azhukov.agent.core.agent.ToolResultFormatter;
import com.azhukov.agent.core.metadata.ModelMetadataService;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/**
 * Package-private helper that owns SSE event sending and stream-metadata
 * formatting for {@link AgentStreamingService}.  Extracted to reduce the
 * 1515-LOC god-class and isolate the JSON-serialisation + emitter-lifecycle
 * concerns.
 *
 * <p>The helper is a plain (non-Spring) object constructed lazily by
 * {@code AgentStreamingService} from dependencies it already holds, so the
 * positional constructor signature used by tests is unchanged.
 */
@Slf4j
class StreamingEventHelper {

    private final ObjectMapper objectMapper;
    private final ToolResultFormatter toolResultFormatter;
    private final ModelMetadataService modelMetadataService;
    private final AgentProperties properties;
    private final UsageTracker usageTracker;
    private final RuntimeConfigService runtimeConfigService;

    StreamingEventHelper(ObjectMapper objectMapper,
                         ToolResultFormatter toolResultFormatter,
                         ModelMetadataService modelMetadataService,
                         AgentProperties properties,
                         UsageTracker usageTracker,
                         RuntimeConfigService runtimeConfigService) {
        this.objectMapper = objectMapper;
        this.toolResultFormatter = toolResultFormatter;
        this.modelMetadataService = modelMetadataService;
        this.properties = properties;
        this.usageTracker = usageTracker;
        this.runtimeConfigService = runtimeConfigService;
    }

    // ── SSE send ────────────────────────────────────────────────────────────

    /**
     * Send a single SSE event to the client.  Silently skips when the client
     * has already disconnected.  On {@link IOException} the stream context is
     * marked as disconnected so subsequent calls are no-ops.
     */
    void send(SseEmitter emitter, StreamEvent event, StreamContext streamCtx) {
        if (streamCtx.isClientDisconnected()) return;
        try {
            emitter.send(SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(event.type())
                .data(objectMapper.writeValueAsString(event)));
        } catch (IllegalStateException e) {
            log.debug("SSE event not sent (emitter completed): {}", e.getMessage());
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
            streamCtx.markDisconnected();
        }
    }

    // ── Metadata event ─────────────────────────────────────────────────────

    /**
     * Overload that uses the usage-tracker estimate for context tokens.
     */
    void sendMetadataEvent(SseEmitter emitter, Session session, StreamContext streamCtx) {
        sendMetadataEvent(emitter, session, streamCtx, 0);
    }

    /**
     * Emit a {@code "metadata"} SSE event carrying the resolved model name,
     * context-window size, and current context-token count.
     *
     * @param lastInputTokens real input-token count from the last model call,
     *                        or {@code 0} to fall back to the usage-tracker estimate
     */
    void sendMetadataEvent(SseEmitter emitter, Session session, StreamContext streamCtx, int lastInputTokens) {
        try {
            String modelUsed = resolveModelUsed(session);
            int contextLength = modelMetadataService.detectContextLength(modelUsed);
            if (contextLength <= 0) {
                contextLength = properties.getContext().getMaxTokens();
            }
            int contextTokens = lastInputTokens > 0
                ? lastInputTokens
                : estimateContextTokens(session.id());
            send(emitter, new StreamEvent("metadata", null, null, null,
                modelUsed, contextTokens, contextLength, null, null, session.id()), streamCtx);
        } catch (Exception e) {
            log.warn("Failed to send stream metadata event: {}", e.getMessage());
        }
    }

    // ── Result preview ─────────────────────────────────────────────────────

    /**
     * Format a tool result for the {@code "tool_result"} SSE event, truncated
     * to 500 characters.
     */
    String formatResultPreview(ToolResult result) {
        String content = toolResultFormatter.formatResult(result);
        int maxLen = 500;
        if (content.length() <= maxLen) return content;
        return content.substring(0, maxLen) + "...";
    }

    // ── Safe completion ────────────────────────────────────────────────────

    /**
     * Complete the emitter normally after an error has already been sent as an
     * SSE event.  Using {@code completeWithError} would propagate the exception
     * to Spring's {@code GlobalExceptionHandler}, which would try to write a
     * JSON error response on a {@code text/event-stream} content type, causing
     * {@code HttpMessageNotWritableException}.
     */
    void safeCompleteWithError(SseEmitter emitter, Throwable error) {
        try {
            // Complete normally — the error has already been sent as an SSE event.
            // Using completeWithError would propagate the exception to Spring's
            // GlobalExceptionHandler, which would try to write a JSON error response
            // on a text/event-stream content type, causing HttpMessageNotWritableException.
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter already completed when trying to complete with error: {}", e.getMessage());
        }
    }

    // ── Private helpers (moved from AgentStreamingService) ─────────────────

    private String resolveModelUsed(Session session) {
        // Per-request override (from /model command or API model field) wins
        String requestOverride = session.getMetadata("modelOverride");
        if (requestOverride != null && !requestOverride.isBlank()) {
            return requestOverride;
        }
        if (session.modelName() != null && !session.modelName().isBlank()) {
            return session.modelName();
        }
        String override = runtimeConfigService.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override;
        }
        if (properties.getModel() != null
            && properties.getModel().getModelName() != null
            && !properties.getModel().getModelName().isBlank()) {
            return properties.getModel().getModelName();
        }
        return "unknown";
    }

    private int estimateContextTokens(UUID sessionId) {
        if (sessionId == null) return 0;
        UsageDto usage = usageTracker.getSessionUsage(sessionId);
        return usage != null ? usage.tokenEstimate() : 0;
    }
}