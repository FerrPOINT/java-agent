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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

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
    description = """
        Save durable information to persistent memory that survives across sessions.
        Memory is injected into future turns, so keep it compact and focused on facts
        that will still matter later.

        WHEN TO SAVE (do this proactively, don't wait to be asked):
        - User corrects you or says 'remember this' / 'don't do that again'
        - User shares a preference, habit, or personal detail (name, role, timezone, coding style)
        - You discover something about the environment (OS, installed tools, project structure)
        - You learn a convention, API quirk, or workflow specific to this user's setup
        - You identify a stable fact that will be useful again in future sessions

        PRIORITY: User preferences and corrections > environment facts > procedural knowledge.
        The most valuable memory prevents the user from having to repeat themselves.

        Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO
        state to memory; use session_search to recall those from past transcripts.
        If you've discovered a new way to do something, solved a problem that could be
        necessary later, save it as a skill with the skill tool.

        TWO TARGETS:
        - 'user': who the user is — name, role, preferences, communication style, pet peeves
        - 'memory': your notes — environment facts, project conventions, tool quirks, lessons learned

        ACTIONS: add (new entry), replace (update existing — old_text identifies it),
        remove (delete — old_text identifies it).

        SKIP: trivial/obvious info, things easily re-discovered, raw data dumps, and temporary task state.
        """,
    toolset = "memory"
)
@Component
@Slf4j
public class MemoryTool implements ToolHandler {

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

        // S7: Build provenance metadata from WriteContext (empty for foreground writes)
        Map<String, String> provenance = WriteContext.buildProvenance();
        if (!provenance.isEmpty()) {
            log.debug("Memory write with provenance: {}", provenance);
        }

        return switch (args.action().toLowerCase()) {
            case "add" -> doAdd(session, target, args, provenance);
            case "replace" -> doReplace(session, target, args, provenance);
            case "remove" -> doRemove(session, target, args, provenance);
            case "read" -> doRead(session, target, args);
            default -> ToolResult.fail("Unknown action: " + args.action());
        };
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
            memoryProvider.store(session.userId(), target, "auto", args.content());
        } catch (IllegalStateException ex) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, ex.getMessage());
        }
        return buildSuccessResponse(session, target, "Entry added.");
    }

    private ToolResult doReplace(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        if (args.old_text() == null || args.old_text().isBlank()) {
            return ToolResult.fail("old_text is required for replace action");
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
        String error = memoryProvider.replace(session.userId(), target, args.old_text(), args.content());
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry replaced.");
    }

    private ToolResult doRemove(Session session, String target, MemoryArgs args, Map<String, String> provenance) {
        if (args.old_text() == null || args.old_text().isBlank()) {
            return ToolResult.fail("old_text is required for remove action");
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
        String error = memoryProvider.remove(session.userId(), target, args.old_text());
        if (error != null) {
            // Fix 4: structured error response with usage info (parity with Hermes)
            return buildErrorResponse(session, target, error);
        }
        return buildSuccessResponse(session, target, "Entry removed.");
    }

    private ToolResult doRead(Session session, String target, MemoryArgs args) {
        String facts = memoryProvider.read(session.userId(), target);
        if (facts == null || facts.isBlank()) {
            return ToolResult.ok("No entries in " + target + " store.");
        }
        return ToolResult.ok(facts);
    }

    /**
     * Build a success response with usage info (parity with Hermes _success_response).
     * Returns: message, usage (percentage + chars/limit), entry_count.
     * Fix 1: Uses getCharCount() which counts pure entries joined by delimiter,
     * NOT read() which includes headers and category prefixes.
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
        sb.append("usage: ").append(pct).append("% — ").append(currentChars).append("/").append(limit).append(" chars");
        sb.append(" | entry_count: ").append(entryCount);
        return ToolResult.ok(sb.toString());
    }

    /**
     * Build an error response with usage info (parity with Hermes error responses).
     * Fix 4: Hermes returns structured error with current_entries and usage.
     * Java returns the error message plus current usage stats.
     */
    private ToolResult buildErrorResponse(Session session, String target, String error) {
        int limit = "user".equalsIgnoreCase(target) ? 1375 : 2200;
        int currentChars = memoryProvider.getCharCount(session.userId(), target);
        int entryCount = memoryProvider.getEntryCount(session.userId(), target);
        int pct = limit > 0 ? Math.min(100, (int) ((double) currentChars / limit * 100)) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(error);
        // Append usage info if the error is about overflow or limits
        if (error.contains("limit") || error.contains("chars") || error.contains("exceed")) {
            sb.append("\nCurrent: ").append(pct).append("% — ").append(currentChars).append("/").append(limit)
              .append(" chars, ").append(entryCount).append(" entries.");
            sb.append("\nConsolidate now: use 'replace' to merge entries or 'remove' stale ones.");
        }
        return ToolResult.fail(sb.toString());
    }

    public record MemoryArgs(
        @ToolParam(description = "The action to perform.", enumValues = {"add", "replace", "remove"}) String action,
        @ToolParam(description = "Which memory store: 'memory' for personal notes, 'user' for user profile.", enumValues = {"memory", "user"}) String target,
        @ToolParam(description = "The entry content. Required for 'add' and 'replace'.", required = false) String content,
        @ToolParam(description = "Short unique substring identifying the entry to replace or remove.", required = false) String old_text,
        @ToolParam(description = "Max results for read", required = false) int limit
    ) {}
}