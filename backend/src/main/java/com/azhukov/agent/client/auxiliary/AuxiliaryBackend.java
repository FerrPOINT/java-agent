package com.azhukov.agent.client.auxiliary;

import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolDefinition;

import java.util.List;
import java.util.Set;

/**
 * A backend provider for auxiliary tasks (compression, titles, vision, review).
 * Each backend provides a model client that can handle specific task types.
 */
public interface AuxiliaryBackend {

    /** Short identifier for this backend (e.g. "main", "openrouter", "anthropic") */
    String name();

    /** Provider type (e.g. "openai-compatible", "anthropic") */
    String provider();

    /** Model name used by this backend */
    String model();

    /** Task types this backend can handle */
    Set<AuxiliaryClient.TaskType> supportedTasks();

    /** Execute a completion request */
    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools);
}