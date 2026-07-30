package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DefaultToolGuardrails implements ToolGuardrails {

    private final AgentProperties properties;
    private final ApprovalQueue approvalQueue;

    // ─── Tool classification (A8) ───

    /** Tools that mutate state — they may make progress across repeated calls, so give more leeway. */
    private static final Set<String> MUTATING_TOOLS = Set.of(
        "write_file", "patch", "delete_file", "terminal",
        "browser_navigate", "browser_click", "browser_type",
        "memory", "skill_manage"
    );

    /** Tools that are idempotent — calling with same args is definitely a loop if repeated. */
    private static final Set<String> IDEMPOTENT_TOOLS = Set.of(
        "read_file", "search_files", "web_search", "web_extract",
        "vision_analyze", "browser_snapshot", "browser_get_images",
        "skills_list", "skill_view", "session_search", "todo"
    );

    // ─── Stateful tracking for loop detection ───

    private static final int IDENTICAL_CALL_HALT_THRESHOLD_IDEMPOTENT = 5;
    private static final int IDENTICAL_CALL_HALT_THRESHOLD_MUTATING = 7;
    private static final int CONSECUTIVE_FAILURE_HALT_THRESHOLD = 3;
    private static final int TOTAL_CALL_WARN_THRESHOLD = 10;

    private final Map<String, Integer> toolCallCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastToolArgs = new ConcurrentHashMap<>();
    private final Map<String, Integer> identicalArgsCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private volatile boolean halted = false;
    private volatile boolean warned = false;

    private Set<String> blockedTools = Set.of();

    @Override
    public boolean isToolAllowed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (blockedTools.contains(toolName)) {
            return false;
        }
        if (halted) {
            return false;
        }
        return true;
    }

    @Override
    public boolean requiresApproval(ToolCall call) {
        if (!properties.getSecurity().isApprovalsEnabled() || call == null) {
            return false;
        }
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }

    /**
     * Creates a pending approval request in the {@link ApprovalQueue} for the
     * given tool call.  The caller (typically {@code DefaultAgentRuntime}) is
     * responsible for waiting for the approval decision before executing the tool.
     *
     * @param sessionId the session requesting approval
     * @param call      the tool call that requires approval
     * @return the created pending approval
     */
    public ApprovalQueue.PendingApproval requestApproval(UUID sessionId, ToolCall call) {
        return approvalQueue.request(sessionId, call, "Approval required for tool: " + call.name());
    }

    /**
     * Checks if the approval for the given session has been granted.
     *
     * @param sessionId the session to check
     * @return {@code true} if the approval has been approved
     */
    public boolean isApproved(UUID sessionId) {
        return approvalQueue.isApproved(sessionId);
    }

    /**
     * Checks if the approval for the given session is still pending (undecided).
     *
     * @param sessionId the session to check
     * @return {@code true} if the approval is still pending
     */
    public boolean isApprovalPending(UUID sessionId) {
        return approvalQueue.isPending(sessionId);
    }

    /**
     * Checks if the approval for the given session has been denied.
     *
     * @param sessionId the session to check
     * @return {@code true} if the approval has been denied
     */
    public boolean isDenied(UUID sessionId) {
        return approvalQueue.isDenied(sessionId);
    }

    @Override
    public void recordToolCall(String toolName, String args, boolean success) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }

        // Track total call count
        int calls = toolCallCount.merge(toolName, 1, Integer::sum);

        // Track identical args (same tool + same args)
        String prevArgs = lastToolArgs.put(toolName, args != null ? args : "");
        if (prevArgs != null && prevArgs.equals(args != null ? args : "")) {
            int identical = identicalArgsCount.merge(toolName, 1, Integer::sum);
            // Use classification for smarter loop detection (A8)
            int threshold = getIdenticalCallHaltThreshold(toolName);
            if (identical >= threshold) {
                halted = true;
            }
        } else {
            identicalArgsCount.put(toolName, 1);
        }

        // Track consecutive failures
        if (!success) {
            int failures = consecutiveFailures.merge(toolName, 1, Integer::sum);
            toolFailureCount.merge(toolName, 1, Integer::sum);
            if (failures >= CONSECUTIVE_FAILURE_HALT_THRESHOLD) {
                halted = true;
            }
        } else {
            consecutiveFailures.put(toolName, 0);
        }

        // Warn threshold: any tool called 10+ times total
        if (!warned && calls >= TOTAL_CALL_WARN_THRESHOLD) {
            warned = true;
        }
    }

    /**
     * Returns the identical-call halt threshold based on tool classification.
     * Idempotent tools: 5 (same args = definite loop).
     * Mutating tools: 7 (they might make progress, give more leeway).
     * Unknown tools: 5 (default, conservative).
     */
    private int getIdenticalCallHaltThreshold(String toolName) {
        if (isMutating(toolName)) {
            return IDENTICAL_CALL_HALT_THRESHOLD_MUTATING;
        }
        return IDENTICAL_CALL_HALT_THRESHOLD_IDEMPOTENT;
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    @Override
    public void reset() {
        toolCallCount.clear();
        toolFailureCount.clear();
        lastToolArgs.clear();
        identicalArgsCount.clear();
        consecutiveFailures.clear();
        halted = false;
        warned = false;
    }

    @Override
    public Set<String> getBlockedTools() {
        return blockedTools;
    }

    @Override
    public void setBlockedTools(Set<String> blockedTools) {
        this.blockedTools = blockedTools != null ? blockedTools : Set.of();
    }

    // ─── Tool classification methods (A8) ───

    @Override
    public boolean isMutating(String toolName) {
        return toolName != null && MUTATING_TOOLS.contains(toolName);
    }

    @Override
    public boolean isIdempotent(String toolName) {
        return toolName != null && IDEMPOTENT_TOOLS.contains(toolName);
    }
}