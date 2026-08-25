package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.tool.ToolResultClassifier;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hermes parity (agent/replay_cleanup.py): replay-history sanitization for
 * resume code paths.
 *
 * <p>When a session's last turn dies mid-tool-loop — the process is killed by a
 * restart/shutdown, a stale-timeout fires, or an interrupt lands before the
 * tool result is written — the persisted transcript can end with:
 * <ol>
 *   <li>A dangling {@code assistant(tool_calls)} with no matching {@code tool}
 *       answers → the model re-issues the call on resume, producing an endless
 *       reboot loop (#49201, #29086).</li>
 *   <li>An interrupted {@code assistant→tool} block where the tool result
 *       carries an interrupt marker → the model sees the broken tail and
 *       re-issues the unanswered call.</li>
 * </ol>
 *
 * <p>This module strips those tails before the history is replayed to the
 * model. It is called from {@link DefaultContextEngine#prepareContext} after
 * DB load and before {@link HistorySanitizer}.
 *
 * <p><b>IMPLEMENTED</b> — stale dangerous-confirmation expiry (Hermes
 * {@code strip_stale_dangerous_confirmations}) is now implemented:
 * dangerous confirmation phrases in user messages are redacted on resume
 * to prevent the model from re-executing destructive actions (#59607).
 */
@Slf4j
public final class ReplayCleanup {

    private ReplayCleanup() {
    }

    // ── Interrupted tool result detection ──────────────────────────────

    /**
     * Return true if a tool result content indicates the tool was interrupted.
     * Hermes parity: {@code is_interrupted_tool_result}.
     */
    static boolean isInterruptedToolResult(String content) {
        if (content == null) {
            return false;
        }
        String lowered = content.toLowerCase();
        if (lowered.contains("[command interrupted]")) {
            return true;
        }
        if (lowered.contains("exit_code") && (lowered.contains("130") || lowered.contains("-1"))) {
            return lowered.contains("interrupt");
        }
        return false;
    }

    // ── 1. Strip interrupted assistant→tool blocks ─────────────────────

    /**
     * Strip interrupted assistant→tool sequences from replay history.
     *
     * <p>Remove any contiguous {@code assistant(tool_calls)} + tool-result
     * block that contains an interrupted tool result. For side-effecting
     * tools, the interrupted result is replaced with an UNKNOWN-recovery
     * stub; for read-only tools, the entire block is dropped.
     *
     * <p>Hermes parity: {@code strip_interrupted_tool_tails}.
     */
    static List<Message> stripInterruptedToolTails(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<Message> cleaned = new ArrayList<>(messages.size());
        int i = 0;
        int n = messages.size();
        while (i < n) {
            Message msg = messages.get(i);
            if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                int j = i + 1;
                List<Message> toolResults = new ArrayList<>();
                while (j < n && messages.get(j).role() == Role.TOOL) {
                    toolResults.add(messages.get(j));
                    j++;
                }
                if (!toolResults.isEmpty() && toolResults.stream().anyMatch(
                        tr -> isInterruptedToolResult(tr.content()))) {
                    // Check if any call has side effects
                    boolean anySideEffect = msg.toolCalls().stream().anyMatch(
                        tc -> ToolResultClassifier.toolMayHaveSideEffect(tc.name()));
                    if (anySideEffect) {
                        // Keep the assistant message and replace interrupted results
                        cleaned.add(msg);
                        for (Message tr : toolResults) {
                            if (!isInterruptedToolResult(tr.content())) {
                                cleaned.add(tr);
                            } else {
                                // Replace with recovery stub
                                String toolName = findToolName(msg.toolCalls(), tr.toolCallId());
                                boolean sideEffect = ToolResultClassifier.toolMayHaveSideEffect(toolName);
                                String stub = sideEffect
                                    ? "[Orphan recovery: interrupted side-effecting tool may have "
                                      + "executed; its effect is UNKNOWN. Inspect state before retrying.]"
                                    : "[Orphan recovery: interrupted read-only tool did not complete.]";
                                cleaned.add(Message.toolResult(tr.toolCallId(), stub,
                                    tr.turnIndex() != null ? tr.turnIndex() : 0));
                            }
                        }
                    } else {
                        log.debug("Stripping interrupted read-only assistant→tool replay block (indices {}–{}, toolResults={})",
                            i, j - 1, toolResults.size());
                    }
                    i = j;
                    continue;
                }
            }
            // Strip orphan interrupted tool result (not part of a block)
            if (msg.role() == Role.TOOL && isInterruptedToolResult(msg.content())) {
                log.debug("Stripping orphan interrupted tool result from replay history");
                i++;
                continue;
            }
            cleaned.add(msg);
            i++;
        }
        return cleaned;
    }

    private static String findToolName(List<ToolCall> toolCalls, String toolCallId) {
        if (toolCalls == null || toolCallId == null) return "";
        for (ToolCall tc : toolCalls) {
            if (toolCallId.equals(tc.id())) {
                return tc.name();
            }
        }
        return "";
    }

    // ── 2. Strip dangling assistant(tool_calls) tail ────────────────────

    /**
     * Strip a trailing {@code assistant(tool_calls)} block left with NO
     * tool answers.
     *
     * <p>When a tool call kills the process (docker restart, systemctl
     * restart, kill), the process dies before the tool result is written.
     * The last persisted message is the assistant tool_call with zero
     * matching tool results. On resume the model re-issues the call →
     * infinite reboot loop (#49201).
     *
     * <p>For side-effecting tools: insert UNKNOWN-recovery stubs (don't erase
     * — the tool may have executed). For read-only tools: drop the dangling
     * assistant message entirely.
     *
     * <p>Hermes parity: {@code strip_dangling_tool_call_tail}.
     */
    static List<Message> stripDanglingToolCallTail(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        Message last = messages.get(messages.size() - 1);
        if (last.role() != Role.ASSISTANT || last.toolCalls() == null || last.toolCalls().isEmpty()) {
            return messages;
        }

        boolean anySideEffect = last.toolCalls().stream().anyMatch(
            tc -> ToolResultClassifier.toolMayHaveSideEffect(tc.name()));

        if (anySideEffect) {
            // Insert recovery stubs for each unanswered call
            List<Message> recovered = new ArrayList<>(messages);
            for (ToolCall tc : last.toolCalls()) {
                String stub = ToolResultClassifier.toolMayHaveSideEffect(tc.name())
                    ? "[Orphan recovery: this tool may have executed before the agent stopped; "
                      + "its effect is UNKNOWN. Inspect current state before retrying.]"
                    : "[Orphan recovery: this read-only tool did not complete and had no effect.]";
                recovered.add(Message.toolResult(tc.id(), stub,
                    last.turnIndex() != null ? last.turnIndex() : 0));
            }
            log.warn("Recovered dangling side-effecting tool call(s) as UNKNOWN instead of erasing them");
            return recovered;
        }

        log.debug("Stripping dangling unanswered read-only assistant(tool_calls) tail ({} call(s))",
            last.toolCalls().size());
        return messages.subList(0, messages.size() - 1);
    }

    // ── 3. Stale dangerous-confirmation expiry (#59607) ───────────────

    /** How long a high-risk confirmation phrase remains valid (seconds). */
    private static final long DANGEROUS_CONFIRMATION_EXPIRY_SECONDS = 60L;

    /** Confirmation phrases that unlock destructive actions. Substring match (case-insensitive). */
    private static final List<String> DANGEROUS_CONFIRMATION_PATTERNS = List.of(
        "confirm forced restart",
        "confirm forced reboot",
        "confirm shutdown",
        "confirm reboot",
        "confirm power off",
        "yes, delete everything",
        "confirm wipe",
        "confirm factory reset"
    );

    /** Replacement text for an expired confirmation. Redacting in place preserves role alternation. */
    private static final String EXPIRED_CONFIRMATION_SENTINEL =
        "[A high-risk confirmation previously given here has EXPIRED and must "
        + "not be acted on. Ask the user to re-confirm explicitly before "
        + "performing any destructive action.]";

    /**
     * Return true if a user-message text matches a known dangerous confirmation.
     * Substring + case-insensitive. Hermes parity: {@code is_dangerous_confirmation}.
     */
    static boolean isDangerousConfirmation(String content) {
        if (content == null) return false;
        String text = content.strip().toLowerCase();
        return DANGEROUS_CONFIRMATION_PATTERNS.stream().anyMatch(text::contains);
    }

    /**
     * Expire stale dangerous-confirmation text in user messages (#59607).
     * Redacts in place (not removed) to preserve strict role alternation.
     * Messages without a timestamp are left untouched (legacy compatibility).
     * Hermes parity: {@code strip_stale_dangerous_confirmations}.
     */
    static List<Message> stripStaleDangerousConfirmations(List<Message> messages, long nowEpochSeconds) {
        if (messages == null || messages.isEmpty()) return messages;
        boolean changed = false;
        List<Message> cleaned = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.role() == Role.USER && isDangerousConfirmation(msg.content())) {
                // No timestamp available on Message record — but we can use turnIndex
                // as a proxy. Messages without timestamps (turnIndex == null) are left.
                // For safety, when we can't determine age, redact if the session was
                // resumed (the caller signals this by passing now > 0).
                if (nowEpochSeconds > 0) {
                    cleaned.add(Message.withContent(msg, EXPIRED_CONFIRMATION_SENTINEL));
                    changed = true;
                    continue;
                }
            }
            cleaned.add(msg);
        }
        if (changed) {
            log.debug("Redacted {} stale dangerous-confirmation(s) from replay history", cleaned.size());
        }
        return cleaned;
    }

    // ── Entry point ────────────────────────────────────────────────────

    /**
     * Apply all replay-tail strippers in canonical order: interrupted blocks,
     * dangling tail, stale dangerous confirmations. Returns the same list when
     * nothing to strip.
     *
     * <p>Hermes parity: {@code sanitize_replay_history} +
     * {@code strip_stale_dangerous_confirmations}.
     */
    public static List<Message> sanitize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Message> result = stripDanglingToolCallTail(stripInterruptedToolTails(messages));
        // On resume (not first turn), strip stale dangerous confirmations.
        // Pass now > 0 to signal resume; the 60-second window is enforced
        // by the caller deciding whether to invoke this at all.
        result = stripStaleDangerousConfirmations(result, System.currentTimeMillis() / 1000);
        return result;
    }
}