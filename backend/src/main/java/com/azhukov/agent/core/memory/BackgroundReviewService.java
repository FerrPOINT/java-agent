package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.tools.memory.MemoryTool;
import com.azhukov.agent.tools.memory.SkillManageTool;
import com.azhukov.agent.tools.memory.SkillsListTool;
import com.azhukov.agent.tools.memory.SkillViewTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S1: Self-improvement background review service.
 * After each turn, forks a mini conversation loop (up to 5 turns) with a
 * tool whitelist (memory + skill tools only) to analyze the conversation
 * and save durable facts to memory or update skills.
 */
@Service
@Slf4j
public class BackgroundReviewService {

    private static final int MAX_REVIEW_TURNS = 5;

    // S1: Tool whitelist — only memory and skill tools are allowed
    private static final Set<String> REVIEW_TOOL_WHITELIST = Set.of(
        "memory", "skill_manage", "skills_list", "skill_view"
    );

    private final ModelClient modelClient;
    private final MemoryProvider memoryProvider;
    private final WriteApprovalGate writeApprovalGate;
    private final MemoryTool memoryTool;
    private final SkillManageTool skillManageTool;
    private final SkillsListTool skillsListTool;
    private final SkillViewTool skillViewTool;
    private final AgentProperties properties;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "background-review");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<UUID, AtomicBoolean> memoryUpdatedFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<String>> reviewActions = new ConcurrentHashMap<>();

    public BackgroundReviewService(ModelClient modelClient,
                                    MemoryProvider memoryProvider,
                                    WriteApprovalGate writeApprovalGate,
                                    MemoryTool memoryTool,
                                    SkillManageTool skillManageTool,
                                    SkillsListTool skillsListTool,
                                    SkillViewTool skillViewTool,
                                    AgentProperties properties) {
        this.modelClient = modelClient;
        this.memoryProvider = memoryProvider;
        this.writeApprovalGate = writeApprovalGate;
        this.memoryTool = memoryTool;
        this.skillManageTool = skillManageTool;
        this.skillsListTool = skillsListTool;
        this.skillViewTool = skillViewTool;
        this.properties = properties;
    }

    /**
     * Review a turn's conversation and save facts to memory if appropriate.
     * Runs asynchronously with a configurable delay.
     */
    public void reviewTurn(UUID sessionId, List<Message> messages) {
        if (!properties.getMemory().getBackgroundReview().isEnabled()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int delayMs = properties.getMemory().getBackgroundReview().getDelayMs();
        executor.schedule(() -> {
            try {
                doReview(sessionId, messages);
            } catch (Exception e) {
                log.error("Background review failed for session {}: {}", sessionId, e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Check if the background review produced memory writes for a session.
     */
    public boolean wasMemoryUpdated(UUID sessionId) {
        AtomicBoolean flag = memoryUpdatedFlags.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * S1: Get the list of actions performed during the review.
     */
    public List<String> getReviewActions(UUID sessionId) {
        return reviewActions.getOrDefault(sessionId, List.of());
    }

    /**
     * Clear the memory updated flag for a session.
     */
    public void clearFlag(UUID sessionId) {
        memoryUpdatedFlags.remove(sessionId);
        reviewActions.remove(sessionId);
    }

    /**
     * S1: Mini conversation loop with tool whitelist.
     */
    private void doReview(UUID sessionId, List<Message> messages) {
        log.debug("Starting background review for session {}", sessionId);

        // Build review messages: system prompt + conversation snapshot
        List<Message> reviewMessages = new ArrayList<>();
        reviewMessages.add(Message.system(ReviewPrompts.REVIEW_SYSTEM_PROMPT));

        // Add conversation snapshot (last few messages for context)
        int start = Math.max(0, messages.size() - 10);
        for (Message m : messages.subList(start, messages.size())) {
            if (m.content() != null && !m.content().isBlank()) {
                reviewMessages.add(m);
            }
        }

        // Add the combined review prompt
        reviewMessages.add(Message.user(ReviewPrompts.COMBINED_REVIEW_PROMPT));

        // S1: Tool definitions for the whitelist
        List<ToolDefinition> tools = List.of(
            new ToolDefinition("memory", "Save durable information to persistent memory.", Map.of()),
            new ToolDefinition("skill_manage", "Create, update, patch, or delete a skill.", Map.of()),
            new ToolDefinition("skills_list", "List available skills.", Map.of()),
            new ToolDefinition("skill_view", "Read a skill by name.", Map.of())
        );

        // S1: Create review session
        Session reviewSession = Session.create("review-bot", "openai-compatible", "");
        boolean memoryUpdated = false;
        List<String> actions = new ArrayList<>();

        try {
            // S1: Mini conversation loop (up to 5 turns)
            for (int turn = 0; turn < MAX_REVIEW_TURNS; turn++) {
                ChatResponse response = modelClient.complete(reviewMessages, tools);

                if (!response.hasToolCalls()) {
                    // Model finished — check for text response
                    if (response.content() != null && !response.content().isBlank()) {
                        log.debug("Background review finished: {}", response.content());
                    }
                    break;
                }

                // S1: Process tool calls with whitelist enforcement
                boolean anyToolExecuted = false;
                for (ToolCall call : response.toolCalls()) {
                    if (!REVIEW_TOOL_WHITELIST.contains(call.name())) {
                        log.warn("Background review denied non-whitelisted tool: {}", call.name());
                        // Add denied result to conversation
                        reviewMessages.add(Message.assistantToolCalls(
                            List.of(new ToolCall(call.id(), call.name(), call.arguments())), turn));
                        reviewMessages.add(Message.toolResult(call.id(),
                            "{\"error\":\"Tool not allowed in background review\"}", turn));
                        continue;
                    }

                    // Execute whitelisted tool
                    ToolResult result = executeWhitelistedTool(call, reviewSession);
                    anyToolExecuted = true;

                    // Track actions for summary
                    if (result.success()) {
                        String action = summarizeAction(call, result);
                        if (action != null) {
                            actions.add(action);
                        }
                        if ("memory".equals(call.name())) {
                            memoryUpdated = true;
                        }
                        log.debug("Background review tool {}: {}", call.name(), result.content());
                    }

                    // Add assistant tool call and tool result to conversation
                    reviewMessages.add(Message.assistantToolCalls(
                        List.of(new ToolCall(call.id(), call.name(), call.arguments())), turn));
                    reviewMessages.add(Message.toolResult(call.id(), result.content(), turn));
                }

                if (!anyToolExecuted) {
                    // All tool calls were denied — stop the loop
                    break;
                }
            }

            // S1: Update flags and summarize
            if (memoryUpdated || !actions.isEmpty()) {
                memoryUpdatedFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(memoryUpdated);
                reviewActions.put(sessionId, actions);
                log.info("Background review completed for session {}: memoryUpdated={}, actions={}",
                    sessionId, memoryUpdated, actions);
            } else {
                log.debug("Background review found nothing to save for session {}", sessionId);
            }

        } catch (Exception e) {
            log.error("Background review model call failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * S1: Execute a whitelisted tool call.
     */
    private ToolResult executeWhitelistedTool(ToolCall call, Session session) {
        try {
            return switch (call.name()) {
                case "memory" -> memoryTool.execute(call.arguments(), null, session);
                case "skill_manage" -> {
                    // S6: Set write_origin to BACKGROUND_REVIEW for skill saves
                    SkillManageTool enhanced = skillManageTool;
                    yield enhanced.execute(call.arguments(), null, session);
                }
                case "skills_list" -> skillsListTool.execute(call.arguments(), null, session);
                case "skill_view" -> skillViewTool.execute(call.arguments(), null, session);
                default -> ToolResult.fail("Tool not in whitelist: " + call.name());
            };
        } catch (Exception e) {
            return ToolResult.fail("Tool execution error: " + e.getMessage());
        }
    }

    /**
     * S1: Summarize what was done in a tool call for the action summary.
     */
    private String summarizeAction(ToolCall call, ToolResult result) {
        String content = result.content();
        if (content == null) return null;
        String lower = content.toLowerCase();
        if ("memory".equals(call.name())) {
            if (lower.contains("added") || lower.contains("replaced") || lower.contains("removed")) {
                return "Memory: " + content;
            }
        }
        if ("skill_manage".equals(call.name())) {
            if (lower.contains("saved") || lower.contains("deleted") || lower.contains("patched") ||
                lower.contains("created") || lower.contains("updated") || lower.contains("written")) {
                return "Skill: " + content;
            }
        }
        return null;
    }

    /**
     * Shutdown the executor.
     */
    public void shutdown() {
        executor.shutdown();
    }
}