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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AgentTool(
    name = "session_search",
    description = "Search past conversation sessions by title or message content using full-text search. Returns session IDs, titles, last updated timestamps and a short snippet.",
    toolset = "memory"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionSearchTool implements ToolHandler {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
        if (args.query() == null || args.query().isBlank()) {
            return ToolResult.fail("Query is required");
        }
        int limit = args.limit() != null && args.limit() > 0 ? Math.min(args.limit(), 20) : 5;
        String q = args.query();

        // Use a LinkedHashMap to deduplicate by session ID while preserving insertion order
        LinkedHashMap<UUID, SessionMatch> matches = new LinkedHashMap<>();

        // P2-15: Try FTS first, fall back to LIKE if FTS fails (e.g. H2 or missing tsvector column)
        List<SessionEntity> titleMatches = searchByTitleFts(q);
        if (titleMatches == null || titleMatches.isEmpty()) {
            titleMatches = sessionRepository.findByTitleContainingIgnoreCase(q);
        }
        for (SessionEntity s : titleMatches) {
            matches.putIfAbsent(s.getId(), new SessionMatch(s.getId(), s.getTitle(), s.getUpdatedAt(), "title match"));
        }

        // P2-15: Try FTS on message content, fall back to LIKE
        List<MessageEntity> messageMatches = searchByContentFts(q);
        if (messageMatches == null || messageMatches.isEmpty()) {
            messageMatches = messageRepository.findByContentContainingIgnoreCase(q);
        }
        for (MessageEntity m : messageMatches) {
            UUID sessionId = m.getSessionId();
            if (matches.containsKey(sessionId)) {
                continue; // already matched via title — skip
            }
            SessionEntity s = sessionRepository.findById(sessionId).orElse(null);
            String title = s != null ? s.getTitle() : null;
            java.time.Instant updated = s != null ? s.getUpdatedAt() : m.getCreatedAt();
            String content = m.getContent();
            String snippet = content != null && content.length() > 120 ? content.substring(0, 120) + "..." : content;
            String snippetText = snippet != null ? snippet.replace("\n", " ") : "";
            matches.put(sessionId, new SessionMatch(sessionId, title, updated, "snippet: " + snippetText));
        }

        List<SessionMatch> sorted = new ArrayList<>(matches.values());
        sorted.sort(Comparator.comparing(SessionMatch::updated, Comparator.nullsLast(Comparator.reverseOrder())));
        List<SessionMatch> result = sorted.stream().limit(limit).toList();

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

    /**
     * P2-15: Attempt full-text search on session titles.
     * Returns null if FTS is unavailable (non-PostgreSQL or missing tsvector column),
     * signaling the caller to fall back to LIKE.
     */
    private List<SessionEntity> searchByTitleFts(String query) {
        try {
            return sessionRepository.searchByTitleFts(query);
        } catch (Exception e) {
            log.debug("FTS title search unavailable, falling back to LIKE: {}", e.getMessage());
            return null;
        }
    }

    /**
     * P2-15: Attempt full-text search on message content.
     * Returns null if FTS is unavailable, signaling the caller to fall back to LIKE.
     */
    private List<MessageEntity> searchByContentFts(String query) {
        try {
            return messageRepository.searchByContentFts(query);
        } catch (Exception e) {
            log.debug("FTS content search unavailable, falling back to LIKE: {}", e.getMessage());
            return null;
        }
    }

    public record SearchArgs(
        @ToolParam(description = "search query") String query,
        @ToolParam(description = "max results (default 5, max 20)") Integer limit
    ) {}

    private record SessionMatch(UUID id, String title, java.time.Instant updated, String matchReason) {}
}