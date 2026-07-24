package com.azhukov.agent.core.sanitizer;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DefaultMessageSanitizer implements MessageSanitizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultMessageSanitizer.class);

    @Override
    public List<Message> sanitize(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Message list must not be empty");
        }

        List<Message> cleaned = new ArrayList<>();

        // 1. System message only at index 0
        if (messages.get(0).role() == Role.SYSTEM) {
            cleaned.add(messages.get(0));
        }

        // 2. Ensure first non-system is user
        int start = cleaned.isEmpty() ? 0 : 1;
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).role() == Role.SYSTEM) {
                if (m.role() != Role.USER) {
                    log.debug("Inserting placeholder user message before {}", m.role());
                    cleaned.add(Message.user("(context)"));
                }
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
                String merged = last.content() + "\n\n" + m.content();
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
}
