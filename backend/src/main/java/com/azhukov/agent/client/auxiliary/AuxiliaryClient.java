package com.azhukov.agent.client.auxiliary;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Provides a fallback chain for side tasks (compression, titles, vision, review).
 * Supports per-task model overrides and multi-provider fallback.
 * <p>
 * Mirrors Hermes' agent/auxiliary_client.py — resolves the best available
 * backend for each task type without duplicating fallback logic.
 */
@Slf4j
public class AuxiliaryClient {

    public enum TaskType {
        COMPRESSION,
        TITLE,
        VISION,
        REVIEW
    }

    private final List<AuxiliaryBackend> backends;
    private final AgentProperties properties;

    public AuxiliaryClient(List<AuxiliaryBackend> backends, AgentProperties properties) {
        this.backends = backends != null ? backends : List.of();
        this.properties = properties;
    }

    /**
     * Execute a side task with fallback chain.
     * Tries each backend in order until one succeeds.
     */
    public ChatResponse complete(TaskType task, List<Message> messages, List<ToolDefinition> tools) {
        AuxiliaryBackend selected = resolveBackend(task);
        if (selected == null) {
            log.warn("No auxiliary backend available for task {}", task);
            return ChatResponse.text("");
        }

        try {
            return selected.complete(messages, tools);
        } catch (Exception e) {
            log.warn("Auxiliary backend {} failed for task {}: {}", selected.name(), task, e.getMessage());
            // Try next backend in fallback chain
            for (AuxiliaryBackend fallback : backends) {
                if (fallback == selected) continue;
                if (!supportsTask(fallback, task)) continue;
                try {
                    return fallback.complete(messages, tools);
                } catch (Exception fe) {
                    log.debug("Fallback {} also failed: {}", fallback.name(), fe.getMessage());
                }
            }
            return ChatResponse.text("");
        }
    }

    /**
     * Async completion with fallback.
     */
    public CompletableFuture<ChatResponse> completeAsync(TaskType task, List<Message> messages, List<ToolDefinition> tools) {
        return CompletableFuture.supplyAsync(() -> complete(task, messages, tools));
    }

    /**
     * Simple text completion for side tasks (no tools).
     */
    public String completeText(TaskType task, String systemPrompt, String userPrompt) {
        ChatResponse response = complete(task,
            List.of(Message.system(systemPrompt), Message.user(userPrompt)),
            List.of());
        return response.content();
    }

    /**
     * Resolve the best backend for a task type, considering per-task model overrides.
     */
    private AuxiliaryBackend resolveBackend(TaskType task) {
        // Check per-task model override in config
        String taskModel = getTaskModel(task);
        String taskProvider = getTaskProvider(task);

        // First, try to find a backend matching the per-task override
        if (taskModel != null || taskProvider != null) {
            for (AuxiliaryBackend backend : backends) {
                if (supportsTask(backend, task) && backendMatches(backend, taskProvider, taskModel)) {
                    return backend;
                }
            }
        }

        // Fall back to first available backend that supports the task
        for (AuxiliaryBackend backend : backends) {
            if (supportsTask(backend, task)) {
                return backend;
            }
        }

        return null;
    }

    private boolean supportsTask(AuxiliaryBackend backend, TaskType task) {
        return backend.supportedTasks().contains(task);
    }

    private boolean backendMatches(AuxiliaryBackend backend, String provider, String model) {
        if (provider != null && !provider.isBlank() && !provider.equalsIgnoreCase(backend.provider())) {
            return false;
        }
        if (model != null && !model.isBlank() && !model.equalsIgnoreCase(backend.model())) {
            return false;
        }
        return true;
    }

    private String getTaskModel(TaskType task) {
        var aux = properties.getAuxiliary();
        if (aux == null) return null;
        return switch (task) {
            case COMPRESSION -> null; // could be extended with per-task config
            case TITLE -> null;
            case VISION -> properties.getVision().getModelName();
            case REVIEW -> null;
        };
    }

    private String getTaskProvider(TaskType task) {
        var aux = properties.getAuxiliary();
        if (aux == null) return null;
        return switch (task) {
            case COMPRESSION -> null;
            case TITLE -> null;
            case VISION -> properties.getVision().getProvider();
            case REVIEW -> null;
        };
    }

    /**
     * Check if any backend is available for a task type.
     */
    public boolean isAvailable(TaskType task) {
        return backends.stream().anyMatch(b -> supportsTask(b, task));
    }
}