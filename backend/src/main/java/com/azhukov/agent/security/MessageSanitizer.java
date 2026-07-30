package com.azhukov.agent.security;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MessageSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
    private static final Pattern SURROGATE = Pattern.compile("[\\ud800-\\udfff]");
    private static final int MAX_MESSAGE_LENGTH = 1_000_000;

    private final SecretRedactor redactor;


    public List<Message> sanitize(List<Message> messages) {
        if (messages == null) return null;
        List<Message> result = new ArrayList<>();
        for (Message m : messages) {
            Message sanitized = sanitize(m);
            // Drop thinking-only assistant messages (no content, no tool calls)
            if (sanitized != null && isThinkingOnly(sanitized)) {
                continue;
            }
            result.add(sanitized);
        }
        // Role-alternation repair: insert placeholder between consecutive same-role messages
        return repairRoleAlternation(result);
    }

    public Message sanitize(Message message) {
        if (message == null) return null;
        String content = message.content();
        content = content == null ? "" : content;
        content = content.replaceAll(CONTROL_CHARS.pattern(), "");
        content = content.replaceAll(SURROGATE.pattern(), "");
        content = java.text.Normalizer.normalize(content, java.text.Normalizer.Form.NFC);
        if (content.length() > MAX_MESSAGE_LENGTH) {
            content = content.substring(0, MAX_MESSAGE_LENGTH) + "\n[truncated]";
        }
        content = redactor.redact(content);

        // Repair tool-call arguments if truncated (unbalanced braces)
        List<ToolCall> toolCalls = message.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolCall> repaired = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                String args = tc.arguments();
                if (args != null && isUnbalancedBraces(args)) {
                    args = repairBraces(args);
                }
                if (args != null) {
                    args = args.replaceAll(SURROGATE.pattern(), "");
                }
                repaired.add(new ToolCall(tc.id(), tc.name(), args != null ? args : tc.arguments()));
            }
            return new Message(message.role(), content, message.toolCall(), repaired, message.toolCallId(), message.turnIndex());
        }
        return Message.withContent(message, content);
    }

    /**
     * Check if assistant message contains only thinking tags (�..
     */
    private boolean isThinkingOnly(Message message) {
        if (message.role() != Role.ASSISTANT) return false;
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) return false;
        String content = message.content();
        if (content == null || content.isBlank()) return true;
        // Check if content is only thinking tags with nothing outside
        String stripped = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        return stripped.isEmpty();
    }

    /**
     * Insert a placeholder message of the opposite role between consecutive same-role messages.
     */
    private List<Message> repairRoleAlternation(List<Message> messages) {
        if (messages.size() < 2) return messages;
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message current = messages.get(i);
            if (!result.isEmpty()) {
                Message previous = result.get(result.size() - 1);
                if (previous.role() == current.role()) {
                    // Insert placeholder of opposite role
                    Role oppositeRole = getOppositeRole(current.role());
                    result.add(new Message(oppositeRole, "(context)", null, null, null, 0));
                }
            }
            result.add(current);
        }
        return result;
    }

    private Role getOppositeRole(Role role) {
        return switch (role) {
            case USER -> Role.ASSISTANT;
            case ASSISTANT -> Role.USER;
            case SYSTEM -> Role.USER;
            case TOOL -> Role.ASSISTANT;
        };
    }

    /**
     * Check if JSON braces are unbalanced (truncated arguments).
     */
    private boolean isUnbalancedBraces(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }
        return braces > 0 || brackets > 0;
    }

    /**
     * Attempt to close unbalanced braces by appending closing characters.
     */
    private String repairBraces(String json) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }
        // If in an unclosed string, close it
        StringBuilder repaired = new StringBuilder(json);
        if (inString) {
            repaired.append('"');
        }
        // Close brackets first, then braces
        for (int i = 0; i < brackets; i++) repaired.append(']');
        for (int i = 0; i < braces; i++) repaired.append('}');
        return repaired.toString();
    }
}