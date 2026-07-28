package com.azhukov.agent.core.memory;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.memory.MemoryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Self-improvement background review service.
 * After each turn, forks an async LLM call with a review prompt to analyze
 * the conversation and save durable facts to memory.
 */
@Service
public class BackgroundReviewService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundReviewService.class);

    private final ModelClient modelClient;
    private final MemoryProvider memoryProvider;
    private final WriteApprovalGate writeApprovalGate;
    private final AgentProperties properties;
    private final ScheduledExecutorService executor;
    private final MemoryTool memoryTool;

    private final ConcurrentHashMap<UUID, AtomicBoolean> memoryUpdatedFlags = new ConcurrentHashMap<>();

    public BackgroundReviewService(ModelClient modelClient,
                                    MemoryProvider memoryProvider,
                                    WriteApprovalGate writeApprovalGate,
                                    MemoryTool memoryTool,
                                    AgentProperties properties) {
        this.modelClient = modelClient;
        this.memoryProvider = memoryProvider;
        this.writeApprovalGate = writeApprovalGate;
        this.memoryTool = memoryTool;
        this.properties = properties;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "background-review");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Review a turn's conversation and save facts to memory if appropriate.
     * Runs asynchronously with a configurable delay.
     * @param sessionId the session ID
     * @param messages the conversation messages from this turn
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
     * @return true if memory was updated
     */
    public boolean wasMemoryUpdated(UUID sessionId) {
        AtomicBoolean flag = memoryUpdatedFlags.get(sessionId);
        return flag != null && flag.get();
    }

    /**
     * Clear the memory updated flag for a session.
     */
    public void clearFlag(UUID sessionId) {
        memoryUpdatedFlags.remove(sessionId);
    }

    private void doReview(UUID sessionId, List<Message> messages) {
        log.debug("Starting background review for session {}", sessionId);

        // Build review prompt with conversation snapshot
        List<Message> reviewMessages = new ArrayList<>();
        reviewMessages.add(Message.system(ReviewPrompts.MEMORY_REVIEW_PROMPT));

        // Add conversation snapshot (last few messages for context)
        int start = Math.max(0, messages.size() - 10);
        for (Message m : messages.subList(start, messages.size())) {
            if (m.content() != null && !m.content().isBlank()) {
                reviewMessages.add(m);
            }
        }

        // Call model with memory tool available
        List<ToolDefinition> tools = List.of(
            new ToolDefinition("memory", "Save durable information to persistent memory.", java.util.Map.of())
        );

        try {
            ChatResponse response = modelClient.complete(reviewMessages, tools);
            boolean memoryUpdated = false;

            // Check if the review produced tool calls (memory writes)
            if (response.hasToolCalls()) {
                for (ToolCall call : response.toolCalls()) {
                    if ("memory".equals(call.name())) {
                        // Execute the memory tool call
                        Session reviewSession = Session.create("review-bot", "openai-compatible", "");
                        ToolResult result = memoryTool.execute(call.arguments(), null, reviewSession);
                        if (result.success()) {
                            memoryUpdated = true;
                            log.debug("Background review wrote to memory: {}", result.content());
                        }
                    }
                }
            }

            if (memoryUpdated) {
                memoryUpdatedFlags.computeIfAbsent(sessionId, k -> new AtomicBoolean(false)).set(true);
                log.info("Background review updated memory for session {}", sessionId);
            } else {
                log.debug("Background review found nothing to save for session {}", sessionId);
            }
        } catch (Exception e) {
            log.error("Background review model call failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Shutdown the executor.
     */
    public void shutdown() {
        executor.shutdown();
    }
}