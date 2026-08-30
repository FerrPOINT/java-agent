package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.memory.WriteContext;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import java.util.Map;
import java.util.Set;

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

    private final MemoryProvider memoryProvider;
    private final WriteApprovalGate writeApprovalGate;

    public MemoryTool(MemoryProvider memoryProvider) {
        this(memoryProvider, null);
    }

    @Autowired
    public MemoryTool(MemoryProvider memoryProvider, WriteApprovalGate writeApprovalGate) {
        this.memoryProvider = memoryProvider;
        this.writeApprovalGate = writeApprovalGate;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        MemoryArgs args = ToolHandler.parseJson(arguments, MemoryArgs.class);
        String target = args.target() != null && !args.target().isBlank() ? args.target() : "memory";

        // Hermes parity (memory_tool.py:1115-1117): accept new_text as alias for content.
        // If content is null/blank and new_text is set, use new_text.
        String effectiveContent = (args.content() != null && !args.content().isBlank())
            ? args.content()
            : args.new_text();

        // H4: Validate target against allowed enum
        if (!VALID_TARGETS.contains(target.toLowerCase())) {
            return ToolResult.fail("Invalid target: '" + target + "'. Must be one of: " + VALID_TARGETS);
        }

        // S7: Build provenance metadata from WriteContext (empty for foreground writes)
        Map<String, String> provenance = WriteContext.buildProvenance();
        if (!provenance.isEmpty()) {
            log.debug("Memory write with provenance: {}", provenance);
        }

        // Hermes parity: batch operations array — applied atomically (all-or-nothing).
        // When operations is provided, the single action/content/old_text fields are ignored.
        if (args.operations() != null && !args.operations().isEmpty()) {
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

        return switch (args.action().toLowerCase()) {
            case "add" -> doAdd(session, target, effectiveArgs, provenance);
            case "replace" -> doReplace(session, target, effectiveArgs, provenance);
            case "remove" -> doRemove(session, target, effectiveArgs, provenance);
            default -> ToolResult.fail("Unknown action: " + args.action());
        };
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
        if (operations.isEmpty()) {
            return ToolResult.fail("operations list is empty.");
        }

        // S3: Write approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                session.userId(), "batch", target, null, null,
                "Batch: " + operations.size() + " operation(s)", origin);
            return ToolResult.ok("Staged for approval (id: " + id + ")");
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
            String error = memoryProvider.applyBatch(session.userId(), target, batch, provenance);
            if (error != null) {
                return buildErrorResponse(session, target, error);
            }
        } catch (UnsupportedOperationException ex) {
            return ToolResult.fail("Atomic batch updates are not supported by memory provider '"
                + memoryProvider.name() + "'.");
        } catch (IllegalStateException ex) {
            return buildErrorResponse(session, target, ex.getMessage());
        }

        log.info("Applied {} atomic batch memory operations for target {}", operations.size(), target);
        return buildSuccessResponse(session, target, "Applied " + operations.size() + " operation(s).");
    }

    private ToolResult doAdd(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        if (args.content() == null || args.content().isBlank()) {
            return ToolResult.fail("content is required for add action");
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                session.userId(), "add", target, args.content(), null,
                args.content().length() > 80 ? args.content().substring(0, 80) + "..." : args.content(),
                origin
            );
            return ToolResult.ok("Staged for approval (id: " + id + ")");
        }
        try {
            // Finding 4.1: Pass provenance to the provider
            memoryProvider.store(session.userId(), target, "auto", args.content(), provenance);
        } catch (IllegalStateException ex) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, ex.getMessage());
        }
        return buildSuccessResponse(session, target, "Entry added.");
    }

    private ToolResult doReplace(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        if (args.old_text() == null || args.old_text().isBlank()) {
            // Hermes parity (memory_tool.py:1054 _missing_old_text_error): a
            // bare "old_text is required" is a dead end for structured-output
            // clients that omit optional fields — return the current inventory
            // plus a retry instruction instead.
            return missingOldTextError(session, target, "replace");
        }
        if (args.content() == null || args.content().isBlank()) {
            return ToolResult.fail("content is required for replace action");
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                session.userId(), "replace", target, args.content(), args.old_text(),
                "Replace: " + (args.old_text().length() > 60 ? args.old_text().substring(0, 60) + "..." : args.old_text()),
                origin
            );
            return ToolResult.ok("Staged for approval (id: " + id + ")");
        }
        // Finding 4.1: Pass provenance to the provider
        String error = memoryProvider.replace(session.userId(), target, args.old_text(), args.content(), provenance);
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry replaced.");
    }

    private ToolResult doRemove(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        if (args.old_text() == null || args.old_text().isBlank()) {
            return missingOldTextError(session, target, "remove");
        }
        // S3: Use WriteContext to determine origin for approval gate
        String origin = WriteContext.effectiveExecutionContext();
        if (writeApprovalGate != null && writeApprovalGate.isEnabled()) {
            var id = writeApprovalGate.stageWrite(
                session.userId(), "remove", target, null, args.old_text(),
                "Remove: " + (args.old_text().length() > 60 ? args.old_text().substring(0, 60) + "..." : args.old_text()),
                origin
            );
            return ToolResult.ok("Staged for approval (id: " + id + ")");
        }
        // Finding 4.1: Pass provenance to the provider
        String error = memoryProvider.remove(session.userId(), target, args.old_text(), provenance);
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry removed.");
    }

    /**
     * Hermes parity (memory_tool.py:1054): recoverable error for replace/remove
     * without old_text — current inventory + retry instruction, not a dead end.
     */
    private ToolResult missingOldTextError(Session session, String target, String action) {
        int limit = "user".equalsIgnoreCase(target) ? 1375 : 2200;
        int current = memoryProvider.getCharCount(session.userId(), target);
        String entries;
        try {
            entries = memoryProvider.read(session.userId(), target);
        } catch (Exception e) {
            entries = "";
        }
        String err = "'" + action + "' needs old_text — a short unique substring of the entry "
            + "to " + action + ". None was provided. Reissue the " + action + " with old_text "
            + "set to part of one of the current_entries below.\n\n"
            + "old_text is required for " + action + " action.\n"
            + "current_entries:\n" + entries
            + "\nusage: " + String.format("%,d", current) + "/" + String.format("%,d", limit);
        return ToolResult.fail(err);
    }

    /**
     * Build a success response with usage info (parity with Hermes _success_response).
     * Returns: message, usage (percentage + chars/limit), entry_count.
     * Fix 1: Uses getCharCount() which counts pure entries joined by delimiter,
     * NOT read() which includes headers and category prefixes.
     * M3: Numbers formatted with comma grouping (e.g. "1,200").
     */
    private ToolResult buildSuccessResponse(Session session, String target, String message) {
        int limit = "user".equalsIgnoreCase(target) ? 1375 : 2200;
        // Fix 1: count pure entry content, not formatted read() output with headers
        int currentChars = memoryProvider.getCharCount(session.userId(), target);
        int entryCount = memoryProvider.getEntryCount(session.userId(), target);
        int pct = limit > 0 ? Math.min(100, (int) ((double) currentChars / limit * 100)) : 0;

        StringBuilder sb = new StringBuilder();
        if (message != null) {
            sb.append(message).append("\n");
        }
        sb.append("usage: ").append(pct).append("% — ").append(formatNumber(currentChars)).append("/")
          .append(formatNumber(limit)).append(" chars");
        sb.append(" | entry_count: ").append(formatNumber(entryCount));
        return ToolResult.ok(sb.toString());
    }

    /**
     * Build an error response with usage info (parity with Hermes error responses).
     * Fix 4: Hermes returns structured error with current_entries and usage.
     * Java returns the error message plus current usage stats.
     * L3: Always append usage info to error responses (not just when error contains keywords).
     * M3: Numbers formatted with comma grouping (e.g. "1,200").
     */
    private ToolResult buildErrorResponse(Session session, String target, String error) {
        int limit = "user".equalsIgnoreCase(target) ? 1375 : 2200;
        int currentChars = memoryProvider.getCharCount(session.userId(), target);
        int entryCount = memoryProvider.getEntryCount(session.userId(), target);
        int pct = limit > 0 ? Math.min(100, (int) ((double) currentChars / limit * 100)) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(error);
        // L3: Always append usage info to error responses
        sb.append("\nCurrent: ").append(pct).append("% — ").append(formatNumber(currentChars)).append("/")
          .append(formatNumber(limit))
          .append(" chars, ").append(formatNumber(entryCount)).append(" entries.");
        sb.append("\nConsolidate now: use 'replace' to merge entries or 'remove' stale ones.");
        return ToolResult.fail(sb.toString());
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