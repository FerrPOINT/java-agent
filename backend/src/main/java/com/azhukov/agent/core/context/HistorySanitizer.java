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
     * Final wire-level sanitization before every model request.
     * <p>
     * Extends structural {@link #sanitize(List)} with template-aware alternation:
     * system/developer/tool rows are exempt, while visible user/assistant rows
     * must alternate for Mistral/llama.cpp style templates. Historical summary
     * system carriers are intentionally left system-role here: providers receive
     * system separately or accept it as an exempt row.
     */
    public static List<Message> sanitizeForModelRequest(List<Message> messages) {
        List<Message> repaired = sanitize(messages);
        if (repaired == null || repaired.isEmpty()) {
            return repaired;
        }
        List<Message> result = new ArrayList<>(repaired.size());
        for (Message message : repaired) {
            if (message.role() == Role.USER
                && !result.isEmpty()
                && result.get(result.size() - 1).role() == Role.USER) {
                Message previous = result.remove(result.size() - 1);
                String previousContent = previous.content() == null ? "" : previous.content();
                String content = message.content() == null ? "" : message.content();
                result.add(Message.withContent(previous, previousContent.isEmpty()
                    ? content : content.isEmpty() ? previousContent : previousContent + "\n\n" + content));
            } else {
                result.add(message);
            }
        }
        return result;
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

        // ── Pass 0: merge consecutive ASSISTANT messages ───────────────────
        // Hermes parity (_repair_orphaned_tool_messages Pass 0): two adjacent
        // assistant messages mean nothing (no tool result, no user turn)
        // separates them. Strict providers reject the split shape: Gemini
        // "Please ensure that function call turn comes immediately after a
        // user turn or after a function response turn", OpenAI "an assistant
        // message with 'tool_calls' must be followed by tool messages".
        // The split arises when compression/rotation drops an intermediate
        // tool result or when interim assistant turns are appended.
        // Union toolCalls (preserve order), concatenate text content.
        List<Message> collapsed = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT
                && !collapsed.isEmpty()
                && collapsed.get(collapsed.size() - 1).role() == Role.ASSISTANT) {
                Message prev = collapsed.remove(collapsed.size() - 1);
                List<ToolCall> prevCalls = prev.toolCalls() == null
                    ? new ArrayList<>() : new ArrayList<>(prev.toolCalls());
                if (msg.toolCalls() != null) {
                    prevCalls.addAll(msg.toolCalls());
                }
                String prevContent = prev.content() == null ? "" : prev.content();
                String newContent = msg.content() == null ? "" : msg.content();
                String joined = prevContent.isEmpty() ? newContent
                    : newContent.isEmpty() ? prevContent
                    : prevContent + "\n" + newContent;
                Message mergedMsg = new Message(Role.ASSISTANT, joined, null,
                    prevCalls.isEmpty() ? null : List.copyOf(prevCalls),
                    null, msg.turnIndex(), Math.max(
                        prev.imageCount() == null ? 0 : prev.imageCount(),
                        msg.imageCount() == null ? 0 : msg.imageCount()));
                collapsed.add(mergedMsg);
                repairs++;
            } else {
                collapsed.add(msg);
            }
        }

        // ── Pass 0.5: alias-dedup tool_calls inside one assistant message ──
        // Two entries whose ids are spellings of the SAME call (e.g. "call_1|fc_1"
        // and "call_1" — Hermes tool_call_id_variants, #63000) make strict
        // providers see one answered twice. Keep the FIRST spelling of each.
        for (int m = 0; m < collapsed.size(); m++) {
            Message msg = collapsed.get(m);
            if (msg.role() != Role.ASSISTANT || msg.toolCalls() == null || msg.toolCalls().size() < 2) {
                continue;
            }
            java.util.Set<String> seenCalls = new HashSet<>();
            List<ToolCall> deduped = new ArrayList<>();
            for (ToolCall tc : msg.toolCalls()) {
                boolean dup = !seenCalls.add(tc.pairingId().isEmpty()
                    ? (tc.id() == null ? "" : tc.id())
                    : tc.pairingId());
                if (!dup) {
                    deduped.add(tc);
                }
            }
            if (deduped.size() != msg.toolCalls().size()) {
                repairs += msg.toolCalls().size() - deduped.size();
                collapsed.set(m, new Message(Role.ASSISTANT, msg.content(), msg.toolCall(),
                    deduped, msg.toolCallId(), msg.turnIndex(), msg.imageCount(), msg.createdAt()));
            }
        }

        // ── Pass 1: repair tool-call / tool-result pairing ────────────────
        // A provider requires both sides of every historical tool exchange.
        // Keep a trailing assistant tool-call as an in-flight executor request;
        // strip only older calls whose result cannot arrive anymore.
        Set<String> resultIds = new HashSet<>();
        for (Message msg : collapsed) {
            if (msg.role() == Role.TOOL) {
                resultIds.addAll(ToolCall.resultIdVariants(msg.toolCallId()));
            }
        }
        int trailingNonTool = collapsed.size() - 1;
        while (trailingNonTool >= 0 && collapsed.get(trailingNonTool).role() == Role.TOOL) {
            trailingNonTool--;
        }

        List<Message> paired = new ArrayList<>(collapsed.size());
        for (int index = 0; index < collapsed.size(); index++) {
            Message msg = collapsed.get(index);
            if (msg.role() == Role.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                && index != trailingNonTool) {
                List<ToolCall> retained = msg.toolCalls().stream()
                    .filter(call -> call.idVariants().stream().anyMatch(resultIds::contains))
                    .toList();
                if (retained.size() != msg.toolCalls().size()) {
                    repairs += msg.toolCalls().size() - retained.size();
                    msg = new Message(Role.ASSISTANT, msg.content(), msg.toolCall(),
                        retained.isEmpty() ? null : retained, null, msg.turnIndex(), msg.imageCount());
                }
            }
            paired.add(msg);
        }

        // ── Pass 2: drop stray TOOL messages ──────────────────────────────
        // Rolling set of known tool-call ids refreshed on each ASSISTANT
        // message; a user turn closes the tool-result run.
        // P-01: matching is alias-aware — a result may reference the call by
        // id, call_id, response_item_id or a composite "call|item" spelling.
        // Alias-group consumption: the whole group is consumed on the first
        // matching result so an alias-duplicate result is dropped as stray.
        Set<String> knownToolIds = new HashSet<>();
        java.util.Map<String, Set<String>> aliasGroups = new java.util.HashMap<>();
        List<Message> filtered = new ArrayList<>(paired.size());
        for (Message msg : paired) {
            if (msg.role() == Role.ASSISTANT) {
                knownToolIds = new HashSet<>();
                aliasGroups = new java.util.HashMap<>();
                if (msg.toolCalls() != null) {
                    for (ToolCall tc : msg.toolCalls()) {
                        Set<String> aliases = tc.idVariants();
                        knownToolIds.addAll(aliases);
                        for (String alias : aliases) {
                            aliasGroups.put(alias, aliases);
                        }
                    }
                }
                filtered.add(msg);
            } else if (msg.role() == Role.TOOL) {
                Set<String> resultVariants = ToolCall.resultIdVariants(msg.toolCallId());
                Set<String> matchedGroup = null;
                for (String variant : resultVariants) {
                    if (knownToolIds.contains(variant)) {
                        matchedGroup = aliasGroups.get(variant);
                        break;
                    }
                }
                if (msg.toolCallId() != null && matchedGroup != null) {
                    filtered.add(msg);
                    // Consume the ENTIRE alias group so a duplicate tool result
                    // referencing the same call through another spelling is
                    // dropped (strict providers reject duplicates with 400).
                    knownToolIds.removeAll(matchedGroup);
                    for (String alias : matchedGroup) {
                        aliasGroups.remove(alias);
                    }
                } else {
                    repairs++;
                    log.debug("HistorySanitizer: dropped orphan tool result (toolCallId={}, contentLen={})",
                        msg.toolCallId(), msg.content() == null ? 0 : msg.content().length());
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
