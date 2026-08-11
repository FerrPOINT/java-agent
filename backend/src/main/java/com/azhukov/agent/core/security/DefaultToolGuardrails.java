package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
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

    // ─── New: pre-call no-progress detection thresholds ───
    private static final int NO_PROGRESS_BLOCK_THRESHOLD_IDEMPOTENT = 5;
    private static final int EXACT_FAILURE_BLOCK_THRESHOLD = 5;
    private static final int SAME_TOOL_FAILURE_HALT_THRESHOLD = 8;
    private static final int EXACT_FAILURE_WARN_THRESHOLD = 2;
    private static final int SAME_TOOL_FAILURE_WARN_THRESHOLD = 3;

    // ─── Loop detection state ───

    private final Map<String, Integer> toolCallCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastToolArgs = new ConcurrentHashMap<>();
    private final Map<String, Integer> identicalArgsCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    // New: canonical-args based tracking (mirrors Hermes ToolCallSignature)
    private final Map<String, Integer> exactFailureCounts = new ConcurrentHashMap<>();
    private final Map<String, Integer> sameToolFailureCounts = new ConcurrentHashMap<>();
    // New: no-progress tracking for idempotent tools (same args → same result)
    private final Map<String, Integer> noProgressCounts = new ConcurrentHashMap<>();
    private volatile boolean halted = false;
    private volatile boolean warned = false;

    private Set<String> blockedTools = Set.of();

    // ─── Pre-call validation (before_call) ───

    /**
     * Checks whether a tool call should be allowed before execution.
     * Returns a GuardrailDecision indicating allow, warn, or block.
     *
     * @param toolName the tool to check
     * @param args     the arguments (may be null)
     * @return a GuardrailDecision with the appropriate action
     */
    public GuardrailDecision checkBeforeCall(String toolName, String args) {
        if (toolName == null || toolName.isBlank()) {
            return GuardrailDecision.block(toolName, "invalid_tool", "Tool name is blank or null");
        }
        if (blockedTools.contains(toolName)) {
            return GuardrailDecision.block(toolName, "blocked_tool", "Tool '" + toolName + "' is in the blocked list");
        }

        String signature = toolSignature(toolName, args);

        // Check for repeated exact failures (same tool + same args failed repeatedly)
        // Check this BEFORE the generic halted flag so we return the specific reason
        int exactFailures = exactFailureCounts.getOrDefault(signature, 0);
        if (exactFailures >= EXACT_FAILURE_BLOCK_THRESHOLD) {
            String msg = "Blocked " + toolName + ": the same tool call failed " + exactFailures +
                " times with identical arguments. Stop retrying it unchanged; change strategy or explain the blocker.";
            halted = true;
            return GuardrailDecision.halt(toolName, "repeated_exact_failure_block", msg);
        }

        // Check for no-progress on idempotent tools (same call returning same result repeatedly)
        if (isIdempotent(toolName)) {
            int noProgress = noProgressCounts.getOrDefault(signature, 0);
            if (noProgress >= NO_PROGRESS_BLOCK_THRESHOLD_IDEMPOTENT) {
                String msg = "Blocked " + toolName + ": this read-only call returned the same result " +
                    noProgress + " times. Stop repeating it unchanged; use the result already provided or try a different query.";
                halted = true;
                return GuardrailDecision.halt(toolName, "idempotent_no_progress_block", msg);
            }
        }

        // Warnings (non-blocking) — check before generic halted so we return the specific reason
        // If halted is already true, escalate warnings to halt with the specific message
        if (exactFailures >= EXACT_FAILURE_WARN_THRESHOLD) {
            String msg = toolName + " has failed " + exactFailures + " times with identical arguments. " +
                "This looks like a loop; inspect the error and change strategy instead of retrying it unchanged.";
            if (halted) {
                return GuardrailDecision.halt(toolName, "repeated_exact_failure_halt", msg);
            }
            return GuardrailDecision.warn(toolName, "repeated_exact_failure_warning", msg);
        }

        int sameToolFailures = sameToolFailureCounts.getOrDefault(toolName, 0);
        if (sameToolFailures >= SAME_TOOL_FAILURE_WARN_THRESHOLD) {
            String msg = failureRecoveryHint(toolName, sameToolFailures);
            if (halted) {
                return GuardrailDecision.halt(toolName, "same_tool_failure_halt", msg);
            }
            return GuardrailDecision.warn(toolName, "same_tool_failure_warning", msg);
        }

        if (noProgressCounts.getOrDefault(signature, 0) >= 2 && isIdempotent(toolName)) {
            String msg = toolName + " returned the same result " + noProgressCounts.get(signature) +
                " times. Use the result already provided or change the query instead of repeating it unchanged.";
            if (halted) {
                return GuardrailDecision.halt(toolName, "idempotent_no_progress_halt", msg);
            }
            return GuardrailDecision.warn(toolName, "idempotent_no_progress_warning", msg);
        }

        // Generic halt check (from consecutive failures or identical args thresholds)
        if (halted) {
            return GuardrailDecision.halt(toolName, "guardrail_halted", "Guardrails have halted further tool calls");
        }

        return GuardrailDecision.allow(toolName);
    }

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
        String normalizedArgs = args != null ? args : "";
        String signature = toolSignature(toolName, args);

        // Track identical args (same tool + same args) — legacy raw-string comparison
        String prevArgs = lastToolArgs.put(toolName, normalizedArgs);
        if (prevArgs != null && prevArgs.equals(normalizedArgs)) {
            int identical = identicalArgsCount.merge(toolName, 1, Integer::sum);
            // Use classification for smarter loop detection (A8)
            int threshold = getIdenticalCallHaltThreshold(toolName);
            if (identical >= threshold) {
                halted = true;
            }
        } else {
            identicalArgsCount.put(toolName, 1);
        }

        // Track consecutive failures with canonical signature (mirrors Hermes)
        if (!success) {
            int failures = consecutiveFailures.merge(toolName, 1, Integer::sum);
            toolFailureCount.merge(toolName, 1, Integer::sum);

            // Track exact failures by signature
            exactFailureCounts.merge(signature, 1, Integer::sum);
            sameToolFailureCounts.merge(toolName, 1, Integer::sum);

            // Clear no-progress for this signature (it failed, not same-result)
            noProgressCounts.remove(signature);

            if (failures >= CONSECUTIVE_FAILURE_HALT_THRESHOLD) {
                halted = true;
            }
            if (sameToolFailureCounts.get(toolName) >= SAME_TOOL_FAILURE_HALT_THRESHOLD) {
                halted = true;
            }
        } else {
            consecutiveFailures.put(toolName, 0);
            // On success, clear failure tracking (mirrors Hermes after_call)
            exactFailureCounts.remove(signature);
            sameToolFailureCounts.remove(toolName);

            // Track no-progress for idempotent tools (same args succeeded again)
            if (isIdempotent(toolName)) {
                // Each successful identical call increments no-progress counter.
                // If args changed (prevArgs != current), the identicalArgsCount resets
                // above, so we use that as a proxy for "same call repeated".
                int identicalCount = identicalArgsCount.getOrDefault(toolName, 1);
                if (identicalCount > 1) {
                    noProgressCounts.merge(signature, 1, Integer::sum);
                } else {
                    // Different args — reset no-progress
                    noProgressCounts.remove(signature);
                }
            }
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
        exactFailureCounts.clear();
        sameToolFailureCounts.clear();
        noProgressCounts.clear();
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

    // ─── Canonical args signature (mirrors Hermes ToolCallSignature) ───

    /**
     * Produces a stable signature for a tool name + args pair.
     * Uses SHA-256 of the tool name + normalized args so tracking is canonical
     * regardless of whitespace differences in the args string.
     */
    private String toolSignature(String toolName, String args) {
        String normalized = (args != null ? args.strip() : "");
        return sha256(toolName + "|" + normalized);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to string hash (shouldn't happen with standard providers)
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Action-oriented guidance for recovering from repeated tool failures.
     * Mirrors Hermes' _tool_failure_recovery_hint.
     */
    private String failureRecoveryHint(String toolName, int count) {
        String common = toolName + " has failed " + count + " times this turn. This looks like a loop. " +
            "Do not switch to text-only replies; keep using tools, but diagnose before retrying. " +
            "First inspect the latest error/output and verify your assumptions. ";
        if ("terminal".equals(toolName)) {
            return common + "For terminal failures, run a small diagnostic such as `pwd && ls -la` " +
                "in the same tool, then try an absolute path, a simpler command, a different " +
                "working directory, or a different tool such as read_file/write_file/patch.";
        }
        return common + "Try different arguments, a narrower query/path, an absolute path when relevant, " +
            "or a different tool that can make progress. If the blocker is external, report " +
            "the blocker after one diagnostic attempt instead of repeating the same failing path.";
    }

    // ─── GuardrailDecision ───

    /**
     * Decision returned by pre-call guardrail checks.
     * Mirrors Hermes' ToolGuardrailDecision.
     */
    public record GuardrailDecision(
        String action,   // allow | warn | block | halt
        String code,
        String message,
        String toolName
    ) {
        public static GuardrailDecision allow(String toolName) {
            return new GuardrailDecision("allow", "allow", "", toolName);
        }

        public static GuardrailDecision warn(String toolName, String code, String message) {
            return new GuardrailDecision("warn", code, message, toolName);
        }

        public static GuardrailDecision block(String toolName, String code, String message) {
            return new GuardrailDecision("block", code, message, toolName);
        }

        public static GuardrailDecision halt(String toolName, String code, String message) {
            return new GuardrailDecision("halt", code, message, toolName);
        }

        public boolean allowsExecution() {
            return "allow".equals(action) || "warn".equals(action);
        }

        public boolean shouldHalt() {
            return "block".equals(action) || "halt".equals(action);
        }
    }
}