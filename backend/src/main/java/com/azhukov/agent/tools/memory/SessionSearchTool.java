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
        "FTS5-backed retrieval over the SQLite message store. No LLM calls — every " +
        "shape returns actual messages from the DB.\n\n" +
        "SOURCE-FIRST LIMIT\n\n" +
        "  This tool searches Hermes conversation history only. It is not evidence " +
        "about the current contents of external sources. If the user provided a " +
        "direct source such as a URL, phone number/contact, app/thread, file path, " +
        "account, website, or live system, inspect that original source before or " +
        "instead of session_search when accessible. Use session_search as secondary " +
        "context for what was previously said, not as primary proof of what the " +
        "source currently contains. If the original source is inaccessible, say so " +
        "and why before falling back to session history. Do not conclude 'not found' " +
        "or 'no prior correspondence' from session_search alone when a direct source " +
        "was provided.\n\n" +
        "FOUR CALLING SHAPES\n\n" +
        "  1) DISCOVERY — pass `query`:\n" +
        "     session_search(query=\"auth refactor\", limit=3)\n" +
        "     Runs FTS5, dedupes hits by session lineage, and returns the top N " +
        "sessions. Adaptive detail is the default: the top-ranked result carries " +
        "full context, while lower-ranked results stay compact. Pass `detail=\"full\"` " +
        "to fully hydrate every result. Every result carries:\n" +
        "       - session_id, title, when, source\n" +
        "       - snippet: FTS5-highlighted match excerpt\n" +
        "       - detail: `full` or `compact`\n" +
        "       - bookend_start/bookend_end: the first/last 3 user+assistant messages " +
        "for full results; empty lists for compact results\n" +
        "       - messages: ±5 messages around the FTS5 match for full results; only " +
        "the flagged anchor message for compact results\n" +
        "       - match_message_id, messages_before, messages_after\n" +
        "     The top result's bookends + window let you reconstruct goal → match → " +
        "resolution immediately. Scroll a compact result when another session looks " +
        "more promising.\n\n" +
        "  2) SCROLL — pass `session_id` + `around_message_id`:\n" +
        "     session_search(session_id=\"...\", around_message_id=12345, window=10)\n" +
        "     Returns a window of ±`window` messages centered on the anchor. No FTS5, " +
        "no bookends — just the slice. Use after a discovery call when you need more " +
        "context than the ±5 default window.\n" +
        "       - To scroll FORWARD: pass messages[-1].id back as around_message_id.\n" +
        "       - To scroll BACKWARD: pass messages[0].id back as around_message_id.\n" +
        "       - The boundary message appears in both windows — orientation marker.\n" +
        "       - When messages_before or messages_after is < window, you're at the " +
        "start or end of the session.\n\n" +
        "  3) READ — pass `session_id` only (no around_message_id):\n" +
        "     session_search(session_id=\"...\", profile=\"work\")\n" +
        "     Dumps the whole session by id (first 20 + last 10 messages when " +
        "large). This is how you resolve an `@session:<profile>/<id>` link the " +
        "user dropped into the chat: split the value on `/` into profile + id " +
        "and call session_search(session_id=id, profile=profile).\n\n" +
        "  4) BROWSE — no args:\n" +
        "     session_search()\n" +
        "     Returns recent sessions chronologically: titles, previews, timestamps. " +
        "Use when the user asks \"what was I working on\" without naming a topic.\n\n" +
        "LINKING THE USER TO A SESSION\n\n" +
        "  When you refer the user to a session, write its `link` value inline in " +
        "your reply — every result carries one, e.g. " +
        "`@session:default/20260722_204335_d62c16`. Copy it verbatim; do not " +
        "reformat it as a markdown link or wrap it in backticks. Hermes renders " +
        "it as a link showing the session's title, so the link IS the title: " +
        "use it as a noun mid-sentence (\"that's @session:default/... — want me " +
        "to pick it up?\"), never alone on its own line, and never alongside the " +
        "title, id, or date spelled out — that shows the user the same session " +
        "twice.\n\n" +
        "FTS5 SYNTAX\n\n" +
        "  AND is the default — multi-word queries require all terms. Use OR explicitly " +
        "for broader recall (`alpha OR beta OR gamma`), quoted phrases for exact match " +
        "(`\"docker networking\"`), boolean (`python NOT java`), or prefix wildcards " +
        "(`deploy*`).\n\n" +
        "WHEN TO USE\n\n" +
        "  Reach for this on questions about Hermes conversation history itself, such " +
        "as \"what did we do about X\", \"where did we leave Y\", or \"find the " +
        "session where Z\". If the user provided a direct source identifier, inspect " +
        "that source first when accessible; session_search can then supply historical " +
        "context. The session DB carries what was said when; external tools show " +
        "current source/world state.",
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
        try {
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

            String json = buildJsonResponse(result, args);
            return ToolResult.ok(json);
        } catch (Exception e) {
            log.error("SessionSearchTool error — args: [{}], error: {}", arguments, e.toString(), e);
            return ToolResult.fail("Session search error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
        @ToolParam(description = "Search query (discovery shape). Keywords, phrases, or boolean expressions to find in past sessions. Omit to browse recent sessions. Ignored when session_id + around_message_id are set (scroll shape).", required = false)
        String query,
        @ToolParam(description = "Discovery shape only. Max sessions to return (default 3, max 10). Bump to 5-10 when the topic likely spans several sessions and you want to pick the right one to scroll into.", required = false)
        Integer limit,
        @ToolParam(description = "Discovery shape only. Temporal bias: 'newest' or 'oldest'. Omit for relevance-only ordering (suitable for exploratory recall — \"what do we know about X\"). Set 'newest' for recency-shaped questions (\"where did we leave X\"). Set 'oldest' for origin-shaped questions (\"how did X start\"). Ignored in scroll and browse shapes.", required = false)
        String sort,
        @ToolParam(description = "Discovery shape only. 'adaptive' (default) fully hydrates the top-ranked result and returns only the anchor message for lower-ranked results. 'full' returns bookends and the complete anchored window for every result.", required = false)
        String detail,
        @ToolParam(description = "Scroll shape. Session to read inside. Use the session_id returned from a prior discovery call. Must be paired with around_message_id. Can also be an @session:<profile>/<id> link.", required = false)
        @JsonProperty("session_id")
        String sessionId,
        @ToolParam(description = "Scroll shape. Message id to center the window on. From a discovery result use match_message_id, or any id seen in a prior window. To scroll forward pass the last window message's id; to scroll backward pass the first.", required = false)
        @JsonProperty("around_message_id")
        String aroundMessageId,
        @ToolParam(description = "Scroll shape only. Messages to return on each side of the anchor (anchor itself always included). Clamped to [1, 20]. Default 5.", required = false)
        Integer window,
        @ToolParam(description = "Optional. Comma-separated roles to include. Discovery defaults to 'user,assistant' (tool output is usually noise). Pass 'user,assistant,tool' to include tool output (debugging tool behaviour) or 'tool' to search tool output only.", required = false)
        @JsonProperty("role_filter")
        String roleFilter,
        @ToolParam(description = "Optional. Read sessions from another profile's database (read-only). Use when resolving an @session:<profile>/<id> link: pass the profile segment here with session_id as the id segment. Omit to use the current profile.", required = false)
        String profile
    ) {}
}