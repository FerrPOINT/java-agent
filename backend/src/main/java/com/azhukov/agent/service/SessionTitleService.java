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
                return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
            }
        } catch (Exception e) {
            log.warn("Failed to generate title via LLM: {}", e.getMessage());
        }
        return normalized.substring(0, MAX_TITLE_LENGTH).trim() + "...";
    }
}
