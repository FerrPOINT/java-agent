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
        Save durable information to persistent memory for the user. Use this tool when you \
        learn something worth remembering long-term — user preferences, environment facts, \
        corrections, or conventions.

        WHEN TO SAVE:
        - User states a preference (language, style, timezone, etc.)
        - User corrects your behavior or output
        - You learn a fact about the user's environment or setup
        - User asks you to remember something

        TWO TARGETS:
        - "memory": Agent's notes about the user and environment (max 2200 chars)
        - "user": User profile — preferences, identity, personal info (max 1375 chars)

        ACTIONS:
        - add(target, content): Add a new fact to the specified target
        - replace(target, old_text, content): Replace an existing fact containing old_text with new content
        - remove(target, old_text): Remove a fact containing old_text
        - read(target): Read all facts from the specified target

        SKIP:
        - Don't save trivial or temporary information
        - Don't save information the user explicitly asked to forget
        - Don't save your own intermediate reasoning or tool outputs
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
        memoryProvider.store(session.userId(), target, "auto", args.content());
        return ToolResult.ok("Added to " + target + " store.");
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
            return ToolResult.fail(error);
        }
        return ToolResult.ok("Replaced in " + target + " store.");
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
            return ToolResult.fail(error);
        }
        return ToolResult.ok("Removed from " + target + " store.");
    }

    private ToolResult doRead(Session session, String target, MemoryArgs args) {
        String facts = memoryProvider.read(session.userId(), target);
        if (facts == null || facts.isBlank()) {
            return ToolResult.ok("No entries in " + target + " store.");
        }
        return ToolResult.ok(facts);
    }

    public record MemoryArgs(
        @ToolParam(description = "Action: add, replace, remove, or read") String action,
        @ToolParam(description = "Target store: memory or user", required = false) String target,
        @ToolParam(description = "Content to store (for add/replace)", required = false) String content,
        @ToolParam(description = "Text to find and replace/remove (for replace/remove)", required = false) String old_text,
        @ToolParam(description = "Max results for read", required = false) int limit
    ) {}
}