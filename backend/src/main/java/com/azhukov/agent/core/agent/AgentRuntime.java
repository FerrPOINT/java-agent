package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;

import java.util.List;
import java.util.UUID;

public interface AgentRuntime {

    default TurnResult runTurn(Session session, String userInput) {
        return runTurn(session, userInput, List.of(), ModelRequestOptions.empty());
    }

    default TurnResult runTurn(Session session, String userInput, List<String> references) {
        return runTurn(session, userInput, references, ModelRequestOptions.empty());
    }

    TurnResult runTurn(Session session, String userInput, List<String> references, ModelRequestOptions options);

    default TurnResult runTurn(Session session, Message userInput, List<String> references, ModelRequestOptions options) {
        return runTurn(session, userInput != null ? userInput.content() : "", references, options);
    }

    ChatResponse run(List<Message> messages, List<ToolDefinition> tools);

    default ChatResponse run(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options) {
        return run(messages, tools);
    }

    default TurnResult runMessages(List<Message> messages, List<ToolDefinition> tools) {
        return runMessages(messages, tools, ModelRequestOptions.empty());
    }

    default TurnResult runMessages(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options) {
        ChatResponse response = run(messages, tools, options);
        if (response == null) {
            return new TurnResult(List.of(), true, null);
        }
        Message assistant = response.hasToolCalls()
            ? Message.assistantWithToolCalls(response.content(), response.toolCalls(), 1)
            : Message.assistant(response.content(), 1);
        return new TurnResult(List.of(assistant), true, null);
    }

    /**
     * Hermes parity (heartbeat.py: "heartbeats only fire into an idle session;
     * a real user message always wins — the tick coalesces"): true while a
     * turn is in progress for the session. Heartbeat/loop watchdogs skip the
     * tick; the interval anchor stays put, so the wakeup fires when the
     * session next goes idle.
     */
    default boolean isSessionBusy(UUID sessionId) {
        return false;
    }
}
