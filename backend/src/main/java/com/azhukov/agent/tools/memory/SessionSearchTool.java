package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Session search tool — 4-mode parity with Hermes session_search.
 * <p>
 * Four calling shapes inferred from arguments:
 * <ol>
 *   <li>DISCOVERY — pass query: FTS + lineage dedup + adaptive detail + bookends</li>
 *   <li>SCROLL — pass session_id + around_message_id: ±N window around anchor</li>
 *   <li>READ — pass session_id only: dump whole session (head 20 + tail 10)</li>
 *   <li>BROWSE — no args: recent sessions chronologically</li>
 * </ol>
 * Delegates logic to {@link SessionSearchService}, builds JSON response here.
 */
@AgentTool(
    name = "session_search",
    description = "Search past sessions stored in the local session DB, or scroll inside one. " +
        "FTS-backed retrieval over the message store. No LLM calls — every shape returns actual messages from the DB. " +
        "Four calling shapes: DISCOVERY (query), SCROLL (session_id + around_message_id), READ (session_id only), BROWSE (no args).",
    toolset = "memory"
)
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionSearchTool implements ToolHandler {

    private final SessionSearchService sessionSearchService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
        UUID currentSessionId = session != null ? session.id() : null;

        SessionSearchService.SearchResult result = sessionSearchService.search(
            args.query(), args.roleFilter(), args.limit(),
            args.sessionId(), args.aroundMessageId(), args.window(),
            args.sort(), args.detail(), args.profile(),
            currentSessionId
        );

        if (!result.success) {
            return ToolResult.fail(result.error != null ? result.error : "Session search failed");
        }

        try {
            String json = buildJsonResponse(result, args);
            return ToolResult.ok(json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize session search result", e);
            return ToolResult.fail("Failed to serialize search results");
        }
    }

    private String buildJsonResponse(SessionSearchService.SearchResult r, SearchArgs args) throws JsonProcessingException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("mode", r.mode);

        switch (r.mode) {
            case "discover" -> {
                response.put("query", r.query);
                response.put("detail", r.detail != null ? r.detail : "adaptive");
                response.put("results", r.discoverResults != null ? r.discoverResults : List.of());
                response.put("count", r.discoverResults != null ? r.discoverResults.size() : 0);
            }
            case "scroll" -> {
                response.put("session_id", r.scrollSessionId);
                response.put("around_message_id", r.scrollAroundMessageId);
                response.put("window", r.scrollWindow);
                response.put("session_meta", r.scrollMeta);
                if (r.scrollView != null) {
                    response.put("messages", r.scrollView.window());
                    response.put("messages_before", r.scrollView.messagesBefore());
                    response.put("messages_after", r.scrollView.messagesAfter());
                } else {
                    response.put("messages", List.of());
                    response.put("messages_before", 0);
                    response.put("messages_after", 0);
                }
            }
            case "read" -> {
                response.put("session_id", r.readSessionId);
                response.put("link", r.readLink);
                response.put("session_meta", r.readMeta);
                response.put("message_count", r.readTotal);
                response.put("truncated", r.readTruncated);
                response.put("messages", r.readMessages);
                if (r.readTruncated) {
                    response.put("message", String.format(
                        "Session has %d messages; showing first %d + last %d. " +
                        "Pass around_message_id (any id above) to scroll the middle.",
                        r.readTotal, r.readHead, r.readTail));
                }
            }
            case "browse" -> {
                response.put("results", r.browseResults != null ? r.browseResults : List.of());
                response.put("count", r.browseResults != null ? r.browseResults.size() : 0);
                response.put("message", String.format("Showing %d most recent sessions. " +
                    "Pass a query= to search, or session_id+around_message_id to scroll.",
                    r.browseResults != null ? r.browseResults.size() : 0));
            }
        }

        return objectMapper.writeValueAsString(response);
    }

    public record SearchArgs(
        @ToolParam(description = "Search query (discovery shape). Keywords or phrases to find in past sessions. Omit to browse recent sessions. Ignored when session_id + around_message_id are set (scroll shape).")
        String query,
        @ToolParam(description = "Discovery shape only. Max sessions to return (default 3, max 10). Bump to 5-10 when the topic likely spans several sessions.")
        Integer limit,
        @ToolParam(description = "Discovery shape only. Temporal bias: 'newest' or 'oldest'. Omit for relevance-only ordering.")
        String sort,
        @ToolParam(description = "Discovery shape only. 'adaptive' (default) fully hydrates the top-ranked result and returns only the anchor message for lower-ranked results. 'full' returns bookends and the complete anchored window for every result.")
        String detail,
        @ToolParam(description = "Scroll shape. Session to read inside. Use the session_id returned from a prior discovery call. Must be paired with around_message_id.")
        @JsonProperty("session_id")
        String sessionId,
        @ToolParam(description = "Scroll shape. Message id to center the window on. From a discovery result use match_message_id, or any id seen in a prior window.")
        @JsonProperty("around_message_id")
        String aroundMessageId,
        @ToolParam(description = "Scroll shape only. Messages to return on each side of the anchor (default 5, clamped 1-20).")
        Integer window,
        @ToolParam(description = "Optional. Comma-separated roles to include. Discovery defaults to 'user,assistant'. Pass 'user,assistant,tool' to include tool output or 'tool' to search tool output only.")
        @JsonProperty("role_filter")
        String roleFilter,
        @ToolParam(description = "Optional. Read sessions from another profile's database (read-only). Use when resolving an @session:<profile>/<id> link.")
        String profile
    ) {}
}