package com.azhukov.agent.tools.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Memory tool — save durable information to persistent memory.
 * <p>
 * S3 fix: Uses {@link WriteContext} to set the correct write origin on the
 * approval gate. During background review, the origin is "background_review"
 * instead of "foreground".
 * S7 fix: Builds provenance metadata from {@link WriteContext} and passes
 * it to the memory provider on store/replace/remove operations.
 */
@AgentTool(
    name = "memory",
    description = "Save durable facts to persistent memory that survive across sessions. Memory is "
        + "injected into every future turn, so keep entries compact and high-signal.\n\n"
        + "HOW: make ALL your changes in ONE call via an 'operations' array (each item: "
        + "{action, content?, old_text?}). The batch applies atomically and the char limit is "
        + "checked only on the FINAL result — so a single call can remove/replace stale entries "
        + "to free room AND add new ones, even when an add alone would overflow. The response "
        + "reports current/limit chars and confirms completion; one batch call finishes the "
        + "update, so don't repeat it. Use the bare action/content/old_text fields only for a "
        + "single lone change.\n\n"
        + "WHEN: save proactively when the user states a preference, correction, or personal "
        + "detail, or you learn a stable fact about their environment, conventions, or workflow. "
        + "Priority: user preferences & corrections > environment facts > procedures. The best "
        + "memory stops the user repeating themselves.\n\n"
        + "IF FULL: an add is rejected with the current entries shown. Reissue as ONE batch that "
        + "removes or shortens enough stale entries and adds the new one together.\n\n"
        + "TARGETS: 'user' = who the user is (name, role, preferences, style). 'memory' = your "
        + "notes (environment, conventions, tool quirks, lessons).\n\n"
        + "SKIP: trivial/obvious info, easily re-discovered facts, raw data dumps, task progress, "
        + "completed-work logs, temporary TODO state (use session_search for those). Reusable "
        + "procedures belong in a skill, not memory.",
    toolset = "memory"
)
@Component
@Slf4j
public class MemoryTool implements ToolHandler {

    /** H4: Valid target values for memory writes. */
    private static final Set<String> VALID_TARGETS = Set.of("memory", "user");
    private static final ObjectMapper MAPPER = ToolHandler.TOOL_ARGS_MAPPER.copy();

    private final MemoryProvider memoryProvider;
    private final WriteApprovalGate writeApprovalGate;
    private final AgentProperties properties;

    public MemoryTool(MemoryProvider memoryProvider) {
        this(memoryProvider, null, null);
    }

    public MemoryTool(MemoryProvider memoryProvider, WriteApprovalGate writeApprovalGate) {
        this(memoryProvider, writeApprovalGate, null);
    }

    @Autowired
    public MemoryTool(MemoryProvider memoryProvider, WriteApprovalGate writeApprovalGate, AgentProperties properties) {
        this.memoryProvider = memoryProvider;
        this.writeApprovalGate = writeApprovalGate;
        this.properties = properties;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        MemoryArgs args;
        try {
            args = ToolHandler.parseJson(arguments == null || arguments.isBlank() ? "{}" : arguments, MemoryArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonFail(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
        String target = normalizeTarget(args.target());

        // Hermes parity (memory_tool.py:1115-1117): accept new_text as alias for content.
        // If both are set, content wins even when it is blank.
        String effectiveContent = args.content() != null ? args.content() : args.new_text();

        // H4: Validate target against allowed enum
        if (!VALID_TARGETS.contains(target)) {
            return jsonFail(Map.of(
                "success", false,
                "error", "Invalid memory target '" + target + "'. Use 'memory' or 'user'."
            ));
        }
        if (!isTargetEnabled(target)) {
            return disabledTargetResponse(target);
        }

        // S7: Build provenance metadata from WriteContext (empty for foreground writes)
        Map<String, String> provenance = WriteContext.buildProvenance();
        if (!provenance.isEmpty()) {
            log.debug("Memory write with provenance: {}", provenance);
        }

        // Hermes parity: batch operations array — applied atomically (all-or-nothing).
        // When operations is provided, the single action/content/old_text fields are ignored.
        if (args.operations() != null) {
            return doBatchOperations(session, target, args.operations(), provenance);
        }

        // Single-op path (legacy)
        if (args.action() == null || args.action().isBlank()) {
            // No action and no operations — treat as read
            return buildSuccessResponse(session, target, null);
        }

        // Resolve new_text alias for single-op path.
        MemoryArgs effectiveArgs = new MemoryArgs(
            args.action(), args.target(), effectiveContent, args.old_text(), args.new_text(),
            args.limit(), args.operations());

        return switch (args.action().toLowerCase(Locale.ROOT)) {
            case "add" -> doAdd(session, target, effectiveArgs, provenance);
            case "replace" -> doReplace(session, target, effectiveArgs, provenance);
            case "remove" -> doRemove(session, target, effectiveArgs, provenance);
            default -> jsonFail(Map.of(
                "success", false,
                "error", "Unknown action '" + args.action() + "'. Use: add, replace, remove"
            ));
        };
    }

    private static String normalizeTarget(String target) {
        if (target == null || target.isBlank()) {
            return "memory";
        }
        return target.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isTargetEnabled(String target) {
        AgentProperties.MemoryProperties memory = properties == null ? null : properties.getMemory();
        if (memory == null) {
            return true;
        }
        return "user".equals(target) ? memory.isUserProfileEnabled() : memory.isMemoryEnabled();
    }

    private static ToolResult disabledTargetResponse(String target) {
        String label = "user".equals(target) ? "USER.md" : "MEMORY.md";
        return jsonFail(Map.of(
            "success", false,
            "error", "Built-in " + label + " writes are disabled in memory config.",
            "target", target
        ));
    }

    /**
     * Hermes parity (memory_tool.py:586 apply_batch): apply a sequence of
     * add/replace/remove ops to one target atomically. All-or-nothing —
     * if any op fails, nothing is written. Budget is checked against the
     * FINAL state, not intermediate states.
     */
    private ToolResult doBatchOperations(Session session, String target,
                                          List<MemoryOperation> operations,
                                          Map<String, String> provenance) {
        String memoryUserId = memoryUserId(session);
        if (operations.isEmpty()) {
            return buildErrorResponse(session, target, "operations list is empty.");
        }

        // S3: Write approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                memoryUserId, "batch", target, toJson(operations), null,
                "Batch: " + operations.size() + " operation(s)", origin);
            return stagedResponse(id, "Staged for approval (memory.write_approval is on). Not yet saved.");
        }

        List<MemoryProvider.MemoryBatchOperation> batch = new ArrayList<>(operations.size());
        for (MemoryOperation op : operations) {
            // Hermes parity (memory_tool.py:626): accept new_text as alias for content in batch ops.
            String opContent = (op != null && op.content() != null && !op.content().isBlank())
                ? op.content()
                : (op != null ? op.new_text() : null);
            batch.add(new MemoryProvider.MemoryBatchOperation(
                op == null ? null : op.action(),
                opContent,
                op == null ? null : op.old_text()));
        }
        try {
            String error = memoryProvider.applyBatch(memoryUserId, target, batch, provenance);
            if (error != null) {
                return buildErrorResponse(session, target, error);
            }
        } catch (UnsupportedOperationException ex) {
            return jsonFail(Map.of(
                "success", false,
                "error", "Atomic batch updates are not supported by memory provider '"
                    + memoryProvider.name() + "'."
            ));
        } catch (IllegalStateException ex) {
            return buildErrorResponse(session, target, ex.getMessage());
        }

        log.info("Applied {} atomic batch memory operations for target {}", operations.size(), target);
        return buildSuccessResponse(session, target, "Applied " + operations.size() + " operation(s).");
    }

    private ToolResult doAdd(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        String memoryUserId = memoryUserId(session);
        if (args.content() == null || args.content().isBlank()) {
            return jsonFail(Map.of(
                "success", false,
                "error", "content is required for 'add' action."
            ));
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                memoryUserId, "add", target, args.content(), null,
                args.content().length() > 80 ? args.content().substring(0, 80) + "..." : args.content(),
                origin
            );
            return stagedResponse(id, "Staged for approval (memory.write_approval is on). Not yet saved.");
        }
        try {
            // Finding 4.1: Pass provenance to the provider
            memoryProvider.store(memoryUserId, target, "auto", args.content(), provenance);
        } catch (IllegalStateException ex) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, ex.getMessage());
        }
        return buildSuccessResponse(session, target, "Entry added.");
    }

    private ToolResult doReplace(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        String memoryUserId = memoryUserId(session);
        if (args.old_text() == null || args.old_text().isBlank()) {
            return missingOldTextResponse(session, target, "replace");
        }
        if (args.content() == null || args.content().isBlank()) {
            return jsonFail(Map.of(
                "success", false,
                "error", "content is required for 'replace' action."
            ));
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                memoryUserId, "replace", target, args.content(), args.old_text(),
                "Replace: " + (args.old_text().length() > 60 ? args.old_text().substring(0, 60) + "..." : args.old_text()),
                origin
            );
            return stagedResponse(id, "Staged for approval (memory.write_approval is on). Not yet saved.");
        }
        // Finding 4.1: Pass provenance to the provider
        String error = memoryProvider.replace(memoryUserId, target, args.old_text(), args.content(), provenance);
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry replaced.");
    }

    private ToolResult doRemove(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        String memoryUserId = memoryUserId(session);
        if (args.old_text() == null || args.old_text().isBlank()) {
            return missingOldTextResponse(session, target, "remove");
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                memoryUserId, "remove", target, null, args.old_text(),
                "Remove: " + (args.old_text().length() > 60 ? args.old_text().substring(0, 60) + "..." : args.old_text()),
                origin
            );
            return stagedResponse(id, "Staged for approval (memory.write_approval is on). Not yet saved.");
        }
        // Finding 4.1: Pass provenance to the provider
        String error = memoryProvider.remove(memoryUserId, target, args.old_text(), provenance);
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry removed.");
    }

    /**
     * Build a success response with usage info (parity with Hermes _success_response).
     * Returns: message, usage (percentage + chars/limit), entry_count.
     * Fix 1: Uses getCharCount() which counts pure entries joined by delimiter,
     * NOT read() which includes headers and category prefixes.
     * M3: Numbers formatted with comma grouping (e.g. "1,200").
     */
    private ToolResult buildSuccessResponse(Session session, String target, String message) {
        String memoryUserId = memoryUserId(session);
        int limit = memoryProvider.getCharLimit(target);
        // Fix 1: count pure entry content, not formatted read() output with headers
        int currentChars = memoryProvider.getCharCount(memoryUserId, target);
        int entryCount = memoryProvider.getEntryCount(memoryUserId, target);
        int pct = limit > 0 ? Math.min(100, (int) ((double) currentChars / limit * 100)) : 0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("done", true);
        response.put("target", target);
        response.put("usage", pct + "% — " + formatNumber(currentChars) + "/" + formatNumber(limit) + " chars");
        response.put("entry_count", entryCount);
        if (message != null) {
            response.put("message", message);
        }
        response.put("note", "Write saved. This update is complete — do not repeat it.");
        return ToolResult.ok(toJson(response));
    }

    /**
     * Build an error response with usage info (parity with Hermes error responses).
     * Fix 4: Hermes returns structured error with current_entries and usage.
     * Java returns the error message plus current usage stats.
     * L3: Always append usage info to error responses (not just when error contains keywords).
     * M3: Numbers formatted with comma grouping (e.g. "1,200").
     */
    private ToolResult buildErrorResponse(Session session, String target, String error) {
        String memoryUserId = memoryUserId(session);
        int limit = memoryProvider.getCharLimit(target);
        int currentChars = memoryProvider.getCharCount(memoryUserId, target);
        int entryCount = memoryProvider.getEntryCount(memoryUserId, target);
        int pct = limit > 0 ? Math.min(100, (int) ((double) currentChars / limit * 100)) : 0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", error);
        response.put("target", target);
        response.put("current_entries", safeRawEntries(memoryUserId, target));
        response.put("usage", pct + "% — " + formatNumber(currentChars) + "/" + formatNumber(limit) + " chars");
        response.put("entry_count", entryCount);
        response.put("hint", "Consolidate now: use 'replace' to merge entries or 'remove' stale ones.");
        return jsonFail(response);
    }

    private ToolResult missingOldTextResponse(Session session, String target, String action) {
        String memoryUserId = memoryUserId(session);
        int limit = memoryProvider.getCharLimit(target);
        int currentChars = memoryProvider.getCharCount(memoryUserId, target);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        String entries;
        try {
            entries = memoryProvider.read(session.userId(), target);
        } catch (Exception e) {
            entries = "";
        }
        response.put("error", "'" + action + "' needs old_text — a short unique substring of the entry "
            + "to " + action + ". None was provided. Reissue the " + action + " with old_text "
            + "set to part of one of the current_entries below.\n\n"
            + "old_text is required for " + action + " action.\n"
            + "current_entries:\n" + entries);
        response.put("current_entries", safeRawEntries(memoryUserId, target));
        response.put("usage", formatNumber(currentChars) + "/" + formatNumber(limit));
        return jsonFail(response);
    }

    private String memoryUserId(Session session) {
        return MemoryScope.userId(session, properties);
    }

    private ToolResult stagedResponse(UUID pendingId, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (pendingId == null) {
            response.put("success", false);
            response.put("staged", false);
            response.put("error", "Failed to stage memory write for approval. Nothing was saved.");
            return jsonFail(response);
        }
        response.put("success", true);
        response.put("staged", true);
        response.put("pending_id", pendingId.toString());
        response.put("message", message);
        return ToolResult.ok(toJson(response));
    }

    private List<String> safeRawEntries(String userId, String target) {
        List<String> entries = memoryProvider.getRawEntries(userId, target);
        return entries == null ? List.of() : entries;
    }

    private static ToolResult jsonFail(Object value) {
        return new ToolResult(false, toJson(value), errorFrom(value));
    }

    private static String errorFrom(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object error = map.get("error");
            if (error instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory response", e);
        }
    }

    /**
     * M3: Format a number with comma grouping (e.g. 1200 → "1,200").
     */
    private static String formatNumber(int value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }

    public record MemoryArgs(
        @ToolParam(description = "The action to perform. Omit when using 'operations' array.", required = false, enumValues = {"add", "replace", "remove"}) String action,
        @ToolParam(description = "Which memory store: 'memory' for personal notes, 'user' for user profile.", enumValues = {"memory", "user"}, required = true) String target,
        @ToolParam(description = "The entry content. Required for 'add' and 'replace'. Alias: 'new_text' is also accepted (mirrors old_text).", required = false) String content,
        @JsonProperty("old_text") String old_text,
        @JsonProperty("new_text") @JsonAlias("new_text") String new_text,
        @ToolParam(description = "Max results for read", required = false) int limit,
        @ToolParam(description = "Batch shape: a list of operations applied atomically in one call (all-or-nothing). Each item: {action, content?, old_text?, new_text?}. When provided, the single action/content/old_text fields are ignored.", required = false)
        List<MemoryOperation> operations
    ) {}

    public record MemoryOperation(
        @ToolParam(description = "Action: add, replace, or remove") String action,
        @ToolParam(description = "Entry content for add/replace. Alias: 'new_text'.", required = false) String content,
        @JsonProperty("old_text") String old_text,
        @JsonProperty("new_text") @JsonAlias("new_text") String new_text
    ) {}
}
