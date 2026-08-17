package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTitleService {

    private static final int MAX_TITLE_LENGTH = 80;

    private final ModelClient modelClient;
    private final SessionRepository sessionRepository;
    private final AgentProperties properties;

    public void maybeUpdateTitle(UUID sessionId, List<Message> messages, boolean isNewSession) {
        if (!isNewSession || !properties.getCore().isAutoTitleSession()) {
            return;
        }
        String firstUser = messages.stream()
            .filter(m -> m.role().name().equalsIgnoreCase("user"))
            .map(Message::content)
            .findFirst()
            .orElse(null);
        if (firstUser == null || firstUser.isBlank()) {
            return;
        }
        String title = generateTitle(firstUser);
        sessionRepository.findById(sessionId).ifPresent(e -> {
            // h91: Don't overwrite manually set session titles with auto-generated ones.
            // Only applies when called for existing sessions (isNewSession=false).
            // When isNewSession=true (first turn), always set the title.
            if (!isNewSession) {
                String existingTitle = e.getTitle();
                if (existingTitle != null && !existingTitle.isBlank()
                    && !"Untitled".equals(existingTitle)
                    && !existingTitle.startsWith("Session ")) {
                    log.debug("Session {} already has a title '{}', not overwriting with auto-generated '{}'",
                        sessionId, existingTitle, title);
                    return;
                }
            }
            e.setTitle(title);
            sessionRepository.save(e);
            log.debug("Updated session {} title to: {}", sessionId, title);
        });
    }

    private String generateTitle(String userMessage) {
        String normalized = userMessage.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_TITLE_LENGTH) {
            return normalized;
        }
        if (modelClient instanceof com.azhukov.agent.client.NoOpModelClient) {
            return normalized.substring(0, MAX_TITLE_LENGTH).trim() + "...";
        }
        try {
            var response = modelClient.complete(
                List.of(
                    Message.system("Generate a concise chat title (max 5 words) in the same language as the user message. Return only the title."),
                    Message.user(userMessage)
                ),
                List.of()
            );
            if (response.content() != null && !response.content().isBlank()) {
                String title = response.content().trim().replaceAll("^['\"]+|['\"]+$", "");
                // h93: If the auto-title generation produces something that looks like an answer
                // (starts with 'The ', 'Here is', 'I ', etc.), reject it and keep the default title.
                if (isAnswerShaped(title)) {
                    log.warn("Auto-title looks like an answer, rejecting: '{}'", title);
                    return normalized.substring(0, MAX_TITLE_LENGTH).trim() + "...";
                }
                return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
            }
        } catch (Exception e) {
            log.warn("Failed to generate title via LLM: {}", e.getMessage());
        }
        return normalized.substring(0, MAX_TITLE_LENGTH).trim() + "...";
    }

    // h93: Check if a generated title looks like an answer rather than a title.
    static boolean isAnswerShaped(String title) {
        if (title == null || title.isEmpty()) return false;
        String trimmed = title.trim();
        String lower = trimmed.toLowerCase();
        // Check for common answer-shaped prefixes
        return lower.startsWith("the ")
            || lower.startsWith("here is")
            || lower.startsWith("here's")
            || lower.startsWith("i ")
            || lower.startsWith("i'll")
            || lower.startsWith("i'd")
            || lower.startsWith("i've")
            || lower.startsWith("sure,")
            || lower.startsWith("sure ")
            || lower.startsWith("certainly")
            || lower.startsWith("of course")
            || lower.startsWith("let me")
            || lower.startsWith("based on")
            || lower.startsWith("according to")
            || lower.startsWith("to answer")
            || lower.startsWith("to respond")
            || lower.startsWith("this is")
            || lower.startsWith("that is")
            || lower.startsWith("it is")
            || lower.startsWith("yes,")
            || lower.startsWith("no,")
            || lower.startsWith("absolutely")
            || lower.startsWith("unfortunately");
    }
}
