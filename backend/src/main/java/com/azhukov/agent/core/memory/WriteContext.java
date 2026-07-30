package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.skill.WriteOrigin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S3/S7: ThreadLocal-based write context that tracks provenance metadata for
 * memory and skill writes. When set, tool implementations use it to tag writes
 * with the correct {@link WriteOrigin} and provenance metadata instead of
 * defaulting to {@code FOREGROUND}.
 * <p>
 * Ported from Hermes' {@code _memory_write_origin} / {@code _memory_write_context}
 * agent attributes, adapted to Java's ThreadLocal pattern.
 */
public final class WriteContext {

    private static final ThreadLocal<WriteContext> CURRENT = new ThreadLocal<>();

    private final WriteOrigin writeOrigin;
    private final String executionContext;
    private final String sessionId;
    private final String parentSessionId;
    private final String platform;
    private final String toolName;

    private WriteContext(WriteOrigin writeOrigin, String executionContext,
                         String sessionId, String parentSessionId,
                         String platform, String toolName) {
        this.writeOrigin = writeOrigin;
        this.executionContext = executionContext;
        this.sessionId = sessionId;
        this.parentSessionId = parentSessionId;
        this.platform = platform;
        this.toolName = toolName;
    }

    /**
     * Set the write context for the current thread.
     */
    public static void set(WriteOrigin writeOrigin, String executionContext,
                           String sessionId, String parentSessionId,
                           String platform, String toolName) {
        CURRENT.set(new WriteContext(writeOrigin, executionContext,
            sessionId, parentSessionId, platform, toolName));
    }

    /**
     * Set background-review write context for the current thread.
     */
    public static void setReviewContext(String sessionId, String parentSessionId, String platform) {
        set(WriteOrigin.BACKGROUND_REVIEW, "background_review",
            sessionId, parentSessionId, platform, "memory");
    }

    /**
     * Get the current thread's write context, or null if none set.
     */
    public static WriteContext current() {
        return CURRENT.get();
    }

    /**
     * Get the effective write origin — defaults to FOREGROUND if no context is set.
     */
    public static WriteOrigin effectiveOrigin() {
        WriteContext ctx = CURRENT.get();
        return ctx != null ? ctx.writeOrigin : WriteOrigin.FOREGROUND;
    }

    /**
     * Get the effective execution context — defaults to "foreground" if no context is set.
     */
    public static String effectiveExecutionContext() {
        WriteContext ctx = CURRENT.get();
        return ctx != null ? ctx.executionContext : "foreground";
    }

    /**
     * Build provenance metadata map from the current context.
     * Returns an empty map if no context is set (foreground writes don't carry provenance).
     */
    public static Map<String, String> buildProvenance() {
        WriteContext ctx = CURRENT.get();
        if (ctx == null) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("write_origin", ctx.writeOrigin.name());
        metadata.put("execution_context", ctx.executionContext);
        if (ctx.sessionId != null && !ctx.sessionId.isBlank()) {
            metadata.put("session_id", ctx.sessionId);
        }
        if (ctx.parentSessionId != null && !ctx.parentSessionId.isBlank()) {
            metadata.put("parent_session_id", ctx.parentSessionId);
        }
        if (ctx.platform != null && !ctx.platform.isBlank()) {
            metadata.put("platform", ctx.platform);
        }
        if (ctx.toolName != null && !ctx.toolName.isBlank()) {
            metadata.put("tool_name", ctx.toolName);
        }
        return metadata;
    }

    /**
     * Clear the write context for the current thread.
     */
    public static void clear() {
        CURRENT.remove();
    }

    public WriteOrigin writeOrigin() { return writeOrigin; }
    public String executionContext() { return executionContext; }
    public String sessionId() { return sessionId; }
    public String parentSessionId() { return parentSessionId; }
    public String platform() { return platform; }
    public String toolName() { return toolName; }
}