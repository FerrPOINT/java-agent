package com.azhukov.agent.core.sanitizer;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DefaultMessageSanitizer implements MessageSanitizer {

    /**
     * Lone surrogate code points are invalid in UTF-8 and crash JSON serialization
     * inside the OpenAI SDK. Replace them with U+FFFD (replacement character).
     * Mirrors Hermes' agent/message_sanitization.py (_SURROGATE_RE).
     */
    private static final Pattern SURROGATE_PATTERN = Pattern.compile("[\\ud800-\\udfff]");

    /**
     * Control characters (except newline, carriage return, tab) that should be
     * stripped from message content to prevent downstream API rejections.
     */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");

    private final ToolCallArgumentRepair argumentRepair = new ToolCallArgumentRepair();

    @Override
    public List<Message> sanitize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Message list must not be empty");
        }

        List<Message> cleaned = new ArrayList<>();

        // 1. System/developer message only at index 0
        if (messages.get(0).role() == Role.SYSTEM || messages.get(0).role() == Role.DEVELOPER) {
            cleaned.add(sanitizeMessageContent(messages.get(0)));
        }

        // 2. Ensure first non-system is user
        int start = cleaned.isEmpty() ? 0 : 1;
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).role() == Role.SYSTEM
                    || cleaned.get(cleaned.size() - 1).role() == Role.DEVELOPER) {
                if (m.role() != Role.USER) {
                    log.debug("Inserting placeholder user message before {}", m.role());
                    cleaned.add(Message.user("(context)"));
                }
            }

            // Sanitize content (surrogates, control chars)
            m = sanitizeMessageContent(m);

            // Repair tool-call arguments if present
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                List<com.azhukov.agent.core.model.ToolCall> repairedCalls = new ArrayList<>();
                for (var tc : m.toolCalls()) {
                    String repairedArgs = argumentRepair.repair(tc.arguments(), tc.name());
                    // Also strip surrogates from tool call fields
                    String repairedId = stripSurrogates(tc.id());
                    String repairedName = stripSurrogates(tc.name());
                    repairedCalls.add(new com.azhukov.agent.core.model.ToolCall(
                        repairedId, repairedName, repairedArgs
                    ));
                }
                m = Message.assistantWithToolCalls(m.content(), repairedCalls, m.turnIndex());
            }
            cleaned.add(m);
        }

        // 3. Collapse consecutive same-role messages (except tool results)
        List<Message> collapsed = new ArrayList<>();
        for (Message m : cleaned) {
            if (collapsed.isEmpty()) {
                collapsed.add(m);
                continue;
            }
            Message last = collapsed.get(collapsed.size() - 1);
            if (last.role() == m.role() && m.role() != Role.TOOL) {
                // Merge content, handling null content safely
                String lastContent = last.content() != null ? last.content() : "";
                String currentContent = m.content() != null ? m.content() : "";
                // If both are empty (e.g., assistant with tool calls), skip merging
                if (lastContent.isEmpty() && currentContent.isEmpty()) {
                    // Keep the one that has tool calls (if any)
                    if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                        collapsed.set(collapsed.size() - 1, m);
                    }
                    continue;
                }
                String merged = lastContent + "\n\n" + currentContent;
                collapsed.set(collapsed.size() - 1, Message.withContent(last, merged));
            } else {
                collapsed.add(m);
            }
        }

        // 4. Ensure no trailing tool messages without a following assistant
        while (!collapsed.isEmpty() && collapsed.get(collapsed.size() - 1).role() == Role.TOOL) {
            log.debug("Removing trailing tool result without assistant response");
            collapsed.remove(collapsed.size() - 1);
        }

        // 5. Must contain at least one user message
        if (collapsed.stream().noneMatch(m -> m.role() == Role.USER)) {
            throw new IllegalArgumentException("Sanitized messages must contain at least one user message");
        }

        return List.copyOf(collapsed);
    }

    /**
     * Sanitizes message content by removing lone surrogates and control characters.
     * Handles null content safely (e.g., assistant messages with only tool calls).
     * Mirrors Hermes' _sanitize_surrogates and _sanitize_messages_surrogates.
     */
    private Message sanitizeMessageContent(Message m) {
        String content = m.content();
        if (content == null) {
            return m; // Nothing to sanitize (e.g., assistant with only tool calls)
        }
        boolean changed = false;
        String result = content;

        // Remove lone surrogates (replace with U+FFFD)
        if (SURROGATE_PATTERN.matcher(result).find()) {
            result = SURROGATE_PATTERN.matcher(result).replaceAll("\ufffd");
            changed = true;
        }

        // Remove control characters (except \n, \r, \t)
        if (CONTROL_CHARS.matcher(result).find()) {
            result = CONTROL_CHARS.matcher(result).replaceAll("");
            changed = true;
        }

        return changed ? Message.withContent(m, result) : m;
    }

    /**
     * Strips lone surrogate code points from a string, replacing with U+FFFD.
     */
    private String stripSurrogates(String s) {
        if (s == null) return null;
        if (SURROGATE_PATTERN.matcher(s).find()) {
            return SURROGATE_PATTERN.matcher(s).replaceAll("\ufffd");
        }
        return s;
    }
}