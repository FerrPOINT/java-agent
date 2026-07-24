package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@AgentTool(
    name = "session_search",
    description = "Search past conversation sessions by title or message content. Returns session IDs, titles, last updated timestamps and a short snippet.",
    toolset = "memory"
)
@Component
public class SessionSearchTool implements ToolHandler {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public SessionSearchTool(SessionRepository sessionRepository, MessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
        if (args.query() == null || args.query().isBlank()) {
            return ToolResult.fail("Query is required");
        }
        int limit = args.limit() != null && args.limit() > 0 ? Math.min(args.limit(), 20) : 5;
        String q = args.query().toLowerCase();

        Set<UUID> matchedIds = new java.util.HashSet<>();
        List<SessionMatch> matches = new ArrayList<>();

        for (SessionEntity s : sessionRepository.findAll()) {
            if (s.getTitle() != null && s.getTitle().toLowerCase().contains(q)) {
                matchedIds.add(s.getId());
                matches.add(new SessionMatch(s.getId(), s.getTitle(), s.getUpdatedAt(), "title match"));
            }
        }

        for (MessageEntity m : messageRepository.findAll()) {
            String content = m.getContent();
            if (content != null && content.toLowerCase().contains(q) && matchedIds.add(m.getSessionId())) {
                SessionEntity s = sessionRepository.findById(m.getSessionId()).orElse(null);
                String title = s != null ? s.getTitle() : null;
                java.time.Instant updated = s != null ? s.getUpdatedAt() : m.getCreatedAt();
                String snippet = content.length() > 120 ? content.substring(0, 120) + "..." : content;
                matches.add(new SessionMatch(m.getSessionId(), title, updated, "snippet: " + snippet.replace("\n", " ")));
            }
        }

        matches.sort(Comparator.comparing(SessionMatch::updated).reversed());
        List<SessionMatch> result = matches.stream().limit(limit).toList();

        if (result.isEmpty()) {
            return ToolResult.ok("No sessions found for: " + args.query());
        }

        String text = result.stream()
            .map(m -> String.format("- %s | %s | updated=%s | %s",
                m.id(),
                m.title() != null ? m.title() : "(no title)",
                m.updated(),
                m.matchReason()))
            .collect(Collectors.joining("\n"));

        return ToolResult.ok(text);
    }

    public record SearchArgs(
        @ToolParam(description = "search query") String query,
        @ToolParam(description = "max results (default 5, max 20)") Integer limit
    ) {}

    private record SessionMatch(UUID id, String title, java.time.Instant updated, String matchReason) {}
}
