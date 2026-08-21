package com.azhukov.agent.core.context;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hermes parity (agent_runtime_helpers.py::_repair_orphaned_tool_messages):
 * repairs conversation histories that would be rejected by strict
 * OpenAI-compatible / Gemini providers with HTTP 400:
 *
 * 1. Stray TOOL messages whose toolCallId doesn't match any preceding
 *    assistant tool_call — dropped (the "Missing corresponding tool call for
 *    tool response message" Gemini failure after context compression /
 *    session rotation, when a screenshot tool-result survives without its
 *    assistant tool_call).
 * 2. Consecutive USER messages — merged with a newline separator so no user
 *    input is lost.
 *
 * Deliberately does NOT rewind assistant(toolCalls)+tool pairs that precede
 * a user message — that pattern is valid when the previous turn completed
 * normally.
 */
@Slf4j
public final class HistorySanitizer {

    private HistorySanitizer() {
    }

    /**
     * Repairs the message list; returns a NEW list (the input is not mutated).
     *
     * @return the sanitized history, or the original reference when no
     *         repairs were necessary.
     */
    public static List<Message> sanitize(List<Message> messages) {
        if (messages == null || messages.size() < 2) {
            return messages;
        }

        int repairs = 0;

        // ── Pass 1: drop stray TOOL messages ──────────────────────────────
        // Rolling set of known tool-call ids refreshed on each ASSISTANT
        // message; a user turn closes the tool-result run.
        Set<String> knownToolIds = new HashSet<>();
        List<Message> filtered = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT) {
                knownToolIds = new HashSet<>();
                if (msg.toolCalls() != null) {
                    for (ToolCall tc : msg.toolCalls()) {
                        if (tc.id() != null) {
                            knownToolIds.add(tc.id());
                        }
                    }
                }
                filtered.add(msg);
            } else if (msg.role() == Role.TOOL) {
                String tcId = msg.toolCallId();
                if (tcId != null && knownToolIds.contains(tcId)) {
                    filtered.add(msg);
                    // Consume the id so a duplicate tool result for the same
                    // tool_call_id is dropped (strict providers reject
                    // duplicates with HTTP 400).
                    knownToolIds.remove(tcId);
                } else {
                    repairs++;
                    log.debug("HistorySanitizer: dropped orphan tool result (toolCallId={}, contentLen={})",
                        tcId, msg.content() == null ? 0 : msg.content().length());
                }
            } else {
                if (msg.role() == Role.USER) {
                    knownToolIds = new HashSet<>();
                }
                filtered.add(msg);
            }
        }

        // ── Pass 2: merge consecutive USER messages ───────────────────────
        List<Message> merged = new ArrayList<>(filtered.size());
        for (Message msg : filtered) {
            if (msg.role() == Role.USER
                && !merged.isEmpty()
                && merged.get(merged.size() - 1).role() == Role.USER) {
                Message prev = merged.remove(merged.size() - 1);
                String prevContent = prev.content() == null ? "" : prev.content();
                String newContent = msg.content() == null ? "" : msg.content();
                String joined = prevContent.isEmpty() ? newContent
                    : newContent.isEmpty() ? prevContent
                    : prevContent + "\n\n" + newContent;
                merged.add(Message.withContent(prev, joined));
                repairs++;
            } else {
                merged.add(msg);
            }
        }

        if (repairs == 0) {
            return messages;
        }
        log.info("HistorySanitizer: repaired conversation history ({} repairs, {} -> {} messages)",
            repairs, messages.size(), merged.size());
        return merged;
    }
}
