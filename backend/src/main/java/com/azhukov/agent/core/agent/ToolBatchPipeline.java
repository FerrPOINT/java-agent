package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.tool.ToolCallValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P-02 (Hermes parity audit 2026-08-27): one shared pre-execution pipeline
 * for tool batches — the single owner of the validation order Hermes runs
 * in {@code conversation_loop.py} before any tool is dispatched:
 *
 * <ol>
 *   <li>duplicate tool-call id repair (uniquify)</li>
 *   <li>tool-name validation with fuzzy repair; valid calls proceed,
 *       invalid calls get error results (h53: never fail the whole batch)</li>
 *   <li>JSON argument validation; truncated → terminal abort signal,
 *       invalid JSON → per-call recovery results</li>
 *   <li>delegate_task cap + duplicate call deduplication</li>
 * </ol>
 *
 * Both runtimes (sync {@code DefaultAgentRuntime} and SSE
 * {@code AgentStreamingService}) route through this class so a validation
 * decision can never drift between the two entry points.
 */
@Slf4j
@Component
public class ToolBatchPipeline {

    /**
     * Outcome of running the pre-execution pipeline over a tool batch.
     *
     * @param executableCalls validated calls safe to dispatch (possibly fewer
     *                        than the input after dedup/cap)
     * @param syntheticResults error/recovery results that must be appended to
     *                         the turn regardless of execution outcome
     * @param truncatedArgs true when a call's arguments were cut off by the
     *                      output-length limit — the caller must abort the
     *                      turn instead of executing anything
     */
    public record PipelineResult(
        List<ToolCall> executableCalls,
        List<Message> syntheticResults,
        boolean truncatedArgs
    ) {
        public static PipelineResult truncated() {
            return new PipelineResult(List.of(), List.of(), true);
        }
    }

    /**
     * Run the canonical validation pipeline over a raw model tool batch.
     *
     * @param rawCalls           tool calls exactly as the model produced them
     * @param registeredToolNames names exposed to the model this session
     * @param turnIndex          turn index for synthetic result rows
     */
    public PipelineResult prepare(List<ToolCall> rawCalls, Set<String> registeredToolNames, int turnIndex) {
        if (rawCalls == null || rawCalls.isEmpty()) {
            return new PipelineResult(List.of(), List.of(), false);
        }

        // 0. Uniquify duplicate tool-call ids before any downstream consumer
        List<ToolCall> toolCalls = new ArrayList<>(rawCalls);
        ToolCallValidator.uniquifyToolCallIds(toolCalls);

        // 1. Tool-name validation — valid calls proceed, invalid get errors (h53)
        List<String> nameErrors = ToolCallValidator.validateToolNames(toolCalls, registeredToolNames);
        List<ToolCall> validCalls = new ArrayList<>();
        List<Message> synthetic = new ArrayList<>();
        if (!nameErrors.isEmpty()) {
            log.warn("Invalid tool calls detected: {}", nameErrors);
            for (ToolCall tc : toolCalls) {
                if (registeredToolNames.contains(tc.name())) {
                    validCalls.add(tc);
                } else {
                    synthetic.add(Message.toolResult(tc.pairingId(),
                        com.azhukov.agent.core.tool.ToolCallValidator.failurePayload(
                            "Tool '" + tc.name() + "' does not exist. Available tools: "
                                + String.join(", ", new java.util.TreeSet<>(registeredToolNames))),
                        turnIndex));
                }
            }
        } else {
            validCalls.addAll(toolCalls);
        }

        // 2. JSON argument validation
        ToolCallValidator.JsonValidationResult jsonResult = ToolCallValidator.validateJsonArgs(validCalls);
        if (jsonResult.truncated()) {
            log.warn("Truncated tool call arguments detected — refusing to execute.");
            return PipelineResult.truncated();
        }
        if (!jsonResult.isValid()) {
            log.warn("Invalid JSON in tool call arguments: {}", jsonResult.errors());
            List<ToolCall> jsonValid = new ArrayList<>();
            for (ToolCall tc : validCalls) {
                boolean hasError = jsonResult.errors().stream()
                    .anyMatch(e -> e.contains("'" + tc.name() + "'"));
                if (hasError) {
                    synthetic.add(Message.toolResult(tc.pairingId(),
                        com.azhukov.agent.core.tool.ToolCallValidator.failurePayload(
                            "Error: Invalid JSON arguments. Please retry with valid JSON. "
                            + "For tools with no required parameters, use an empty object: {}"),
                        turnIndex));
                } else {
                    // The call itself parsed, but a sibling invalid call makes the
                    // whole batch unverifiable — skip it with a recovery result.
                    synthetic.add(Message.toolResult(tc.pairingId(),
                        com.azhukov.agent.core.tool.ToolCallValidator.failurePayload(
                            "Skipped: other tool call in this response had invalid JSON."),
                        turnIndex));
                }
            }
            return new PipelineResult(List.of(), synthetic, false);
        }

        // 3. Delegate cap + duplicate deduplication
        List<ToolCall> capped = ToolCallValidator.capDelegateTaskCalls(validCalls);
        capped = ToolCallValidator.deduplicateToolCalls(capped);

        return new PipelineResult(capped, synthetic, false);
    }

    /** Convenience overload preserving insertion order of the registered set. */
    public PipelineResult prepare(List<ToolCall> rawCalls, List<String> registeredToolNames, int turnIndex) {
        return prepare(rawCalls, new LinkedHashSet<>(registeredToolNames), turnIndex);
    }
}
