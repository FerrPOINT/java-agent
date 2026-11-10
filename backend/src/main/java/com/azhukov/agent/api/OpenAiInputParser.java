package com.azhukov.agent.api;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts OpenAI input and history to messages for Responses and Runs. */
final class OpenAiInputParser {
    private final ObjectMapper objectMapper;

    OpenAiInputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParseResult parseInput(Object rawInput) {
        if (rawInput instanceof String text) {
            return ParseResult.success(List.of(Message.user(OpenAiContentNormalizer.normalizeConversationText(text))));
        }
        if (!(rawInput instanceof List<?> list)) {
            return ParseResult.error("'input' must be a string or array");
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            ParseResult parsed = parseInputItem(item, "input[" + i + "]");
            if (parsed.error() != null) {
                return parsed;
            }
            messages.addAll(parsed.messages());
        }
        return ParseResult.success(messages);
    }

    private ParseResult parseInputItem(Object item, String path) {
        try {
            if (item instanceof String text) {
                return ParseResult.success(List.of(Message.user(OpenAiContentNormalizer.normalizeConversationText(text))));
            }
            if (!(item instanceof Map<?, ?> map)) {
                return ParseResult.error(path + " must be a string or object");
            }
            String type = stringValue(map.get("type")).toLowerCase(Locale.ROOT);
            if ("function_call_output".equals(type)) {
                String rawCallId = callIdFrom(map);
                if (!hasText(rawCallId)) {
                    return ParseResult.error(path + " function_call_output is missing call_id");
                }
                String callId = ToolCall.responsesCallId(rawCallId, "", "", 0);
                String output = OpenAiContentNormalizer.normalizeConversationText(map.get("output"));
                return ParseResult.success(List.of(Message.toolResult(callId, output, 0)));
            }
            if ("function_call".equals(type)) {
                String rawCallId = callIdFrom(map);
                if (!hasText(rawCallId)) {
                    return ParseResult.error(path + " function_call is missing call_id");
                }
                String rawName = stringValue(map.get("name"));
                if (!hasText(rawName)) {
                    return ParseResult.error(path + " function_call is missing name");
                }
                String name = ToolCall.sanitizeReplayedFunctionName(rawName);
                String arguments = normalizeToolCallArguments(map.get("arguments"));
                String callId = ToolCall.responsesCallId(rawCallId, name, arguments, 0);
                return ParseResult.success(List.of(Message.assistantToolCalls(
                    List.of(new ToolCall(callId, name, arguments)), 0)));
            }
            if (!type.isBlank() && !"message".equals(type) && !map.containsKey("role")) {
                return ParseResult.error("Unsupported input item type '" + type + "'");
            }
            String role = hasText(stringValue(map.get("role"))) ? stringValue(map.get("role")) : "user";
            Object content = map.containsKey("content") ? map.get("content") : "";
            String toolCallId = map.containsKey("tool_call_id")
                ? stringValue(map.get("tool_call_id"))
                : stringValue(map.get("toolCallId"));
            return ParseResult.success(List.of(toMessage(role, content, toolCallId, rawToolCalls(map))));
        } catch (IllegalArgumentException e) {
            return ParseResult.error(e.getMessage(), OpenAiContentNormalizer.errorCode(e), path + ".content");
        }
    }

    ParseResult parseHistory(Object rawHistory) {
        if (!(rawHistory instanceof List<?> history)) {
            return ParseResult.error("'conversation_history' must be an array of message objects");
        }
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Object item = history.get(i);
            if (!(item instanceof Map<?, ?> map)
                || !map.containsKey("role")
                || !map.containsKey("content")) {
                return ParseResult.error(
                    "conversation_history[" + i + "] must have 'role' and 'content' fields");
            }
            try {
                String role = stringValue(map.get("role"));
                String toolCallId = map.containsKey("tool_call_id")
                    ? stringValue(map.get("tool_call_id"))
                    : stringValue(map.get("toolCallId"));
                messages.add(toMessage(role, map.get("content"), toolCallId, rawToolCalls(map)));
            } catch (IllegalArgumentException e) {
                return ParseResult.error(e.getMessage(), OpenAiContentNormalizer.errorCode(e),
                    "conversation_history[" + i + "].content");
            }
        }
        return ParseResult.success(messages);
    }

    private Message toMessage(String rawRole, Object content, String toolCallId, Object rawToolCalls) {
        String role = rawRole != null ? rawRole.trim().toLowerCase(Locale.ROOT) : "user";
        OpenAiContentNormalizer.NormalizedConversationContent normalized =
            switch (role) {
                case "system", "developer" -> new OpenAiContentNormalizer.NormalizedConversationContent(
                    OpenAiContentNormalizer.normalizeSystemText(content),
                    0);
                default -> OpenAiContentNormalizer.normalizeConversationContent(content);
            };
        String text = normalized.text();
        int imageCount = normalized.imageCount();
        Message message = switch (role) {
            case "system" -> Message.system(text);
            case "developer" -> Message.developer(text);
            case "assistant" -> {
                List<ToolCall> toolCalls = parseToolCalls(rawToolCalls);
                yield toolCalls.isEmpty()
                    ? Message.assistant(text, 0)
                    : Message.assistantWithToolCalls(text, toolCalls, 0);
            }
            case "tool" -> Message.toolResult(
                hasText(toolCallId) ? ToolCall.responsesCallId(toolCallId, "", "", 0) : "",
                text,
                0);
            default -> imageCount > 0 ? Message.userWithImages(text, imageCount) : Message.user(text);
        };
        return imageCount > 0 && message.role() != Role.USER
            ? Message.withImageCount(message, imageCount)
            : message;
    }

    private Object rawToolCalls(Map<?, ?> map) {
        return map.containsKey("tool_calls") ? map.get("tool_calls") : map.get("toolCalls");
    }

    private List<ToolCall> parseToolCalls(Object rawToolCalls) {
        if (!(rawToolCalls instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<ToolCall> toolCalls = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Object raw = rawList.get(i);
            if (!(raw instanceof Map<?, ?> callMap)) {
                continue;
            }
            Object rawFunction = callMap.get("function");
            Map<?, ?> functionMap = rawFunction instanceof Map<?, ?> map ? map : Map.of();
            String name = stringValue(functionMap.get("name"));
            if (!hasText(name)) {
                name = stringValue(callMap.get("name"));
            }
            if (!hasText(name)) {
                continue;
            }
            String arguments = normalizeToolCallArguments(
                functionMap.containsKey("arguments") ? functionMap.get("arguments") : callMap.get("arguments"));
            String id = ToolCall.responsesCallId(callIdFrom(callMap), name, arguments, i);
            name = ToolCall.sanitizeReplayedFunctionName(name);
            toolCalls.add(new ToolCall(id, name, arguments));
        }
        return toolCalls.isEmpty() ? List.of() : List.copyOf(toolCalls);
    }

    private String normalizeToolCallArguments(Object arguments) {
        if (arguments instanceof Map<?, ?> || arguments instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(arguments);
            } catch (JsonProcessingException ignored) {
                // Fall back to String.valueOf below.
            }
        }
        String value = stringValue(arguments);
        return hasText(value) ? value : "{}";
    }

    private String callIdFrom(Map<?, ?> map) {
        String value = stringValue(map.get("call_id"));
        if (hasText(value)) {
            return value;
        }
        value = stringValue(map.get("callId"));
        if (hasText(value)) {
            return value;
        }
        return stringValue(map.get("id"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Parsed messages or an error with the original request parameter path. */
    record ParseResult(List<Message> messages, String error, String code, String param) {
        static ParseResult success(List<Message> messages) {
            return new ParseResult(messages, null, null, null);
        }

        static ParseResult error(String error) {
            return new ParseResult(List.of(), error, null, null);
        }

        static ParseResult error(String error, String code, String param) {
            return new ParseResult(List.of(), error, code, param);
        }
    }
}
