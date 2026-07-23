package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.api.dto.OpenAiChatResponse;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/chat/completions")
public class ChatCompletionsController {

    private final AgentRuntime agentRuntime;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;

    public ChatCompletionsController(AgentRuntime agentRuntime,
                                     ToolRegistry toolRegistry,
                                     PromptBuilder promptBuilder) {
        this.agentRuntime = agentRuntime;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping
    public OpenAiChatResponse completions(@Valid @RequestBody OpenAiChatRequest request) {
        Session session = Session.create("openai-user", "openai-compatible", request.model());
        List<Message> messages = new ArrayList<>();
        messages.add(promptBuilder.buildSystemMessage(session));
        for (OpenAiChatRequest.OpenAiMessage m : request.messages()) {
            messages.add(toMessage(m));
        }

        List<ToolDefinition> tools = request.tools() != null
            ? request.tools().stream().map(this::toToolDefinition).toList()
            : toolRegistry.getDefinitions();

        ChatResponse response = agentRuntime.run(messages, tools);
        return toOpenAiResponse(request.model(), response);
    }

    private Message toMessage(OpenAiChatRequest.OpenAiMessage m) {
        String role = m.role() != null ? m.role() : "user";
        return switch (role) {
            case "system" -> Message.system(m.content());
            case "assistant" -> Message.assistant(m.content(), 0);
            case "tool" -> Message.toolResult(m.toolCallId(), m.content(), 0);
            default -> Message.user(m.content());
        };
    }

    private ToolDefinition toToolDefinition(OpenAiChatRequest.OpenAiTool tool) {
        return new ToolDefinition(
            tool.function().name(),
            tool.function().description(),
            tool.function().parameters()
        );
    }

    private OpenAiChatResponse toOpenAiResponse(String model, ChatResponse response) {
        String content = response.content() != null ? response.content() : "";
        List<OpenAiChatResponse.ToolCall> toolCalls = response.toolCalls() != null
            ? response.toolCalls().stream().map(this::toOpenAiToolCall).toList()
            : List.of();
        OpenAiChatResponse.Message message = toolCalls.isEmpty()
            ? new OpenAiChatResponse.Message("assistant", content, null)
            : new OpenAiChatResponse.Message("assistant", null, toolCalls);

        return new OpenAiChatResponse(
            UUID.randomUUID().toString(),
            "chat.completion",
            Instant.now().getEpochSecond(),
            model,
            List.of(new OpenAiChatResponse.Choice(0, message, "stop")),
            new OpenAiChatResponse.Usage(0, 0, 0)
        );
    }

    private OpenAiChatResponse.ToolCall toOpenAiToolCall(ToolCall tc) {
        return new OpenAiChatResponse.ToolCall(
            tc.id() != null ? tc.id() : UUID.randomUUID().toString(),
            "function",
            new OpenAiChatResponse.Function(tc.name(), tc.arguments())
        );
    }
}
