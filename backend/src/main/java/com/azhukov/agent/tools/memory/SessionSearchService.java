package com.azhukov.agent.tools.memory;

import com.azhukov.agent.core.agent.SessionLineageService;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Session search service — 4-mode session_search parity with Hermes.
 * <p>
 * Ported from Hermes {@code tools/session_search_tool.py} (1321 lines).
 * Four calling shapes inferred from arguments:
 * <ol>
 *   <li>DISCOVERY — pass query: FTS + lineage dedup + adaptive detail + bookends</li>
 *   <li>SCROLL — pass session_id + around_message_id: ±N window around anchor</li>
 *   <li>READ — pass session_id only: dump whole session (head 20 + tail 10)</li>
 *   <li>BROWSE — no args: recent sessions chronologically</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSearchService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final SessionLineageService sessionLineageService;

    static final List<String> HIDDEN_SESSION_SOURCES = List.of("kanban", "subagent", "tool");
    static final List<String> DEMOTED_SESSION_SOURCES = List.of("cron");
    static final Set<String> FRESH_RESET_END_REASONS = Set.of(
        "new_session", "idle_timeout", "daily_reset", "gateway_reset"
    );
    static final List<String> COMPACTION_PREFIXES = List.of(
        "[CONTEXT COMPACTION", "[CONTEXT SUMMARY]:"
    );

    // ── Mode routing ──

    public SearchResult search(
        String query,
        String roleFilter,
        Integer limit,
        String sessionId,
        String aroundMessageId,
        Integer window,
        String sort,
        String detail,
        String profile,
        UUID currentSessionId
    ) {
        // Parse @session:profile/id link format
        String resolvedProfile = profile;
        String resolvedSessionId = sessionId;
        if (sessionId != null && sessionId.contains("/")) {
            String[] parts = sessionId.split("/", 2);
            if (parts.length == 2 && !parts[1].isBlank()) {
                resolvedSessionId = parts[1].trim();
                if ((resolvedProfile == null || resolvedProfile.isBlank()) && !parts[0].isBlank()) {
                    resolvedProfile = parts[0].trim();
                    if (resolvedProfile.startsWith("@session:")) {
                        resolvedProfile = resolvedProfile.substring("@session:".length());
                    }
                }
            }
        }

        // Parse UUID for session_id
        UUID sessionUuid = null;
        if (resolvedSessionId != null && !resolvedSessionId.isBlank()) {
            try {
                sessionUuid = UUID.fromString(resolvedSessionId.trim());
            } catch (IllegalArgumentException e) {
                return SearchResult.error("Invalid session_id format: " + resolvedSessionId);
            }
        }

        // Scroll: session_id + around_message_id
        if (sessionUuid != null && aroundMessageId != null && !aroundMessageId.isBlank()) {
            int win = clamp(window != null ? window : 5, 1, 20);
            UUID anchorUuid;
            try {
                anchorUuid = UUID.fromString(aroundMessageId.trim());
            } catch (IllegalArgumentException e) {
                return SearchResult.error("Invalid around_message_id format: " + aroundMessageId);
            }
            return scroll(sessionUuid, anchorUuid, win, currentSessionId);
        }

        // Read: session_id only
        if (sessionUuid != null) {
            return readSession(sessionUuid, resolvedProfile);
        }

        int lim = limit != null ? clamp(limit, 1, 10) : 3;

        // Browse: no query
        if (query == null || query.isBlank()) {
            return browse(lim, currentSessionId, resolvedProfile);
        }

        // Discovery
        List<String> roleList = null;
        if (roleFilter != null && !roleFilter.isBlank()) {
            roleList = Arrays.stream(roleFilter.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        }
        String sortNorm = null;
        if (sort != null) {
            String c = sort.trim().toLowerCase();
            if (c.equals("newest") || c.equals("oldest")) sortNorm = c;
        }
        String detailNorm = (detail != null && detail.trim().equalsIgnoreCase("full")) ? "full" : "adaptive";

        return discover(query.trim(), roleList, lim, sortNorm, detailNorm, currentSessionId, resolvedProfile);
    }

    // ── DISCOVERY ──

    private SearchResult discover(String query, List<String> roleFilter, int limit, String sort,
                                   String detail, UUID currentSessionId, String linkProfile) {
        UUID currentLineageRoot = currentSessionId != null ? resolveLineageRoot(currentSessionId) : null;

        // FTS content search (excluding hidden sources)
        List<MessageEntity> ftsMessages;
        try {
            ftsMessages = messageRepository.searchByContentFtsExcludingSources(query, HIDDEN_SESSION_SOURCES);
        } catch (Exception e) {
            log.debug("FTS content search failed, falling back to LIKE: {}", e.getMessage());
            ftsMessages = messageRepository.findByContentContainingIgnoreCase(query);
        }

        // FTS title search
        List<SessionEntity> ftsSessions;
        try {
            ftsSessions = sessionRepository.searchByTitleFtsExcludingSources(query, HIDDEN_SESSION_SOURCES);
        } catch (Exception e) {
            log.debug("FTS title search failed, falling back to LIKE: {}", e.getMessage());
            ftsSessions = sessionRepository.findByTitleContainingIgnoreCase(query);
        }

        // Exact title match
        SessionEntity titleMatch = null;
        try {
            titleMatch = sessionRepository.findByTitleIgnoreCase(query);
        } catch (Exception e) {
            log.debug("Title exact match failed: {}", e.getMessage());
        }

        LinkedHashMap<UUID, DiscoverMatch> seen = new LinkedHashMap<>();
        List<DiscoverResult> results = new ArrayList<>();

        // Title match first
        if (titleMatch != null) {
            UUID titleLineage = resolveLineageRoot(titleMatch.getId());
            if (currentLineageRoot == null || !titleLineage.equals(currentLineageRoot)) {
                seen.put(titleLineage, new DiscoverMatch(titleMatch.getId(), titleMatch.getId(), null, "title"));
                results.add(buildTitleResult(titleMatch, linkProfile));
            }
        }

        // Demote cron rows below interactive
        List<MessageEntity> sortedMessages = new ArrayList<>(ftsMessages);
        sortedMessages.sort((a, b) -> {
            SessionEntity sa = sessionRepository.findById(a.getSessionId()).orElse(null);
            SessionEntity sb = sessionRepository.findById(b.getSessionId()).orElse(null);
            int sourceA = sa != null && DEMOTED_SESSION_SOURCES.contains(sa.getSource()) ? 1 : 0;
            int sourceB = sb != null && DEMOTED_SESSION_SOURCES.contains(sb.getSource()) ? 1 : 0;
            return Integer.compare(sourceA, sourceB);
        });

        // Process FTS message hits
        for (MessageEntity msg : sortedMessages) {
            if (seen.size() >= limit) break;
            UUID rawSid = msg.getSessionId();
            UUID resolvedSid = resolveLineageRoot(rawSid);

            if (currentLineageRoot != null && resolvedSid.equals(currentLineageRoot)) {
                boolean isCompacted = Boolean.FALSE.equals(msg.getActive()) && Boolean.TRUE.equals(msg.getCompacted());
                boolean isEnded = isSessionLeftLiveContext(rawSid);
                if (!isCompacted && !isEnded) continue;
            }
            if (currentSessionId != null && rawSid.equals(currentSessionId)) {
                boolean isCompacted = Boolean.FALSE.equals(msg.getActive()) && Boolean.TRUE.equals(msg.getCompacted());
                if (!isCompacted) continue;
            }
            if (!seen.containsKey(resolvedSid)) {
                seen.put(resolvedSid, new DiscoverMatch(rawSid, resolvedSid, msg.getId(), "content"));
            }
        }

        // FTS title matches
        for (SessionEntity s : ftsSessions) {
            if (seen.size() >= limit) break;
            UUID resolvedSid = resolveLineageRoot(s.getId());
            if (currentLineageRoot != null && resolvedSid.equals(currentLineageRoot)) {
                if (!isSessionLeftLiveContext(s.getId())) continue;
            }
            if (!seen.containsKey(resolvedSid)) {
                seen.put(resolvedSid, new DiscoverMatch(s.getId(), resolvedSid, null, "title_fts"));
            }
        }

        // Hydrate results
        int resultIdx = 0;
        for (var entry : seen.entrySet()) {
            if (resultIdx >= limit) break;
            UUID lineageRoot = entry.getKey();
            DiscoverMatch match = entry.getValue();
            if ("title".equals(match.matchType())) { resultIdx++; continue; }

            UUID hitSid = match.rawSessionId();
            UUID msgId = match.messageId();
            AnchoredView view = msgId != null ? getAnchoredView(hitSid, msgId, 5, 3) : null;
            SessionEntity sessionMeta = sessionRepository.findById(lineageRoot).orElse(null);
            String resultDetail = ("full".equals(detail) || resultIdx == 0) ? "full" : "compact";

            List<ShapedMessage> windowMsgs = view != null ? view.window() : List.of();
            if ("compact".equals(resultDetail) && msgId != null) {
                windowMsgs = windowMsgs.stream()
                    .filter(m -> msgId.equals(m.id()))
                    .collect(Collectors.toList());
            }

            String snippet = "";
            if (msgId != null) {
                MessageEntity msgEntity = messageRepository.findById(msgId).orElse(null);
                if (msgEntity != null && msgEntity.getContent() != null) {
                    snippet = truncate(msgEntity.getContent(), 200);
                }
            }

            results.add(new DiscoverResult(
                hitSid,
                sessionMeta != null ? formatTimestamp(sessionMeta.getCreatedAt()) : "unknown",
                sessionMeta != null && sessionMeta.getSource() != null ? sessionMeta.getSource() : "unknown",
                sessionMeta != null && sessionMeta.getModelName() != null ? sessionMeta.getModelName() : "unknown",
                sessionMeta != null ? sessionMeta.getTitle() : null,
                null,
                msgId,
                snippet,
                "full".equals(resultDetail) && view != null ? filterCompaction(view.bookendStart()) : List.of(),
                windowMsgs,
                "full".equals(resultDetail) && view != null ? filterCompaction(view.bookendEnd()) : List.of(),
                view != null ? view.messagesBefore() : 0,
                view != null ? view.messagesAfter() : 0,
                resultDetail,
                !lineageRoot.equals(hitSid) ? lineageRoot : null,
                sessionLink(hitSid, linkProfile)
            ));
            resultIdx++;
        }

        if (sort != null && !results.isEmpty()) {
            List<DiscoverResult> sortedResults = new ArrayList<>(results);
            Comparator<DiscoverResult> cmp = "newest".equals(sort)
                ? Comparator.comparing(DiscoverResult::when, Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing(DiscoverResult::when, Comparator.nullsLast(Comparator.naturalOrder()));
            sortedResults.sort(cmp);
            return SearchResult.discover(query, detail, sortedResults);
        }

        return SearchResult.discover(query, detail, results);
    }

    // ── SCROLL ──

    private SearchResult scroll(UUID sessionId, UUID aroundMessageId, int window, UUID currentSessionId) {
        SessionEntity meta = sessionRepository.findById(sessionId).orElse(null);
        if (meta == null) return SearchResult.error("session_id not found: " + sessionId);

        if (currentSessionId != null) {
            UUID anchorLineage = resolveLineageRoot(sessionId);
            UUID currentLineage = resolveLineageRoot(currentSessionId);
            if (anchorLineage != null && anchorLineage.equals(currentLineage)) {
                MessageEntity anchor = messageRepository.findById(aroundMessageId).orElse(null);
                boolean isCompacted = anchor != null && Boolean.FALSE.equals(anchor.getActive()) && Boolean.TRUE.equals(anchor.getCompacted());
                boolean isOutOfContext = isSessionLeftLiveContext(sessionId);
                if (!isCompacted && !isOutOfContext) {
                    return SearchResult.error("scroll rejected: anchor lives in the current session lineage (already in your active context)");
                }
            }
        }

        AnchoredView view = getAnchoredView(sessionId, aroundMessageId, window, 0);
        if (view == null || view.window().isEmpty()) {
            return SearchResult.error("around_message_id " + aroundMessageId + " not in session_id " + sessionId);
        }

        Map<String, Object> metaMap = new LinkedHashMap<>();
        metaMap.put("when", formatTimestamp(meta.getCreatedAt()));
        metaMap.put("source", meta.getSource());
        metaMap.put("model", meta.getModelName());
        metaMap.put("title", meta.getTitle());

        return SearchResult.scroll(sessionId, aroundMessageId, window, view, metaMap);
    }

    // ── READ ──

    private SearchResult readSession(UUID sessionId, String linkProfile) {
        SessionEntity meta = sessionRepository.findById(sessionId).orElse(null);
        if (meta == null) return SearchResult.error("session_id not found: " + sessionId);

        List<MessageEntity> rows = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // H-SYNC: Limit content per message to prevent oversized tool output.
        // Hermes uses max_content_len=4000 for anchor, 1200 for bookends.
        // READ mode returns head+tail (30 messages) — use 2000 to keep total < 60KB.
        List<ShapedMessage> shaped = rows.stream().map(m -> shapeMessage(m, null, 2000)).collect(Collectors.toList());

        int total = shaped.size();
        int head = 20, tail = 10;
        boolean truncated = total > head + tail;
        List<ShapedMessage> windowMsgs = truncated
            ? concat(shaped.subList(0, head), shaped.subList(total - tail, total))
            : shaped;

        Map<String, Object> metaMap = new LinkedHashMap<>();
        metaMap.put("when", formatTimestamp(meta.getCreatedAt()));
        metaMap.put("source", meta.getSource());
        metaMap.put("model", meta.getModelName());
        metaMap.put("title", meta.getTitle());

        return SearchResult.read(sessionId, metaMap, windowMsgs, total, truncated, head, tail, sessionLink(sessionId, linkProfile));
    }

    // ── BROWSE ──

    private SearchResult browse(int limit, UUID currentSessionId, String linkProfile) {
        List<SessionEntity> sessions = sessionRepository.listRecentExcludingSources(
            HIDDEN_SESSION_SOURCES, PageRequest.of(0, limit + 15)
        );
        UUID currentRoot = currentSessionId != null ? resolveLineageRoot(currentSessionId) : null;

        List<BrowseResult> results = new ArrayList<>();
        for (SessionEntity s : sessions) {
            if (currentSessionId != null && s.getId().equals(currentSessionId)) continue;
            if (currentRoot != null && s.getId().equals(currentRoot) && isCompressionEnded(s)) continue;

            results.add(new BrowseResult(
                s.getId(),
                sessionLink(s.getId(), linkProfile),
                s.getTitle(),
                s.getSource() != null ? s.getSource() : "",
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : "",
                s.getLastActive() != null ? s.getLastActive().toString() : (s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : ""),
                s.getMessageCount() != null ? s.getMessageCount() : 0,
                s.getPreview() != null ? s.getPreview() : ""
            ));
            if (results.size() >= limit) break;
        }

        return SearchResult.browse(results);
    }

    // ── Anchored view ──

    private AnchoredView getAnchoredView(UUID sessionId, UUID anchorId, int windowSize, int bookendSize) {
        List<MessageEntity> allMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (allMessages.isEmpty()) return null;

        int anchorIdx = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).getId().equals(anchorId)) { anchorIdx = i; break; }
        }
        if (anchorIdx == -1) return null;

        int start = Math.max(0, anchorIdx - windowSize);
        int end = Math.min(allMessages.size(), anchorIdx + windowSize + 1);

        List<ShapedMessage> windowMsgs = allMessages.subList(start, end).stream()
            .map(m -> shapeMessage(m, anchorId, 2000))
            .collect(Collectors.toList());

        int messagesBefore = anchorIdx - start;
        int messagesAfter = end - anchorIdx - 1;

        // Bookends: first/last N user+assistant messages
        List<MessageEntity> uaMsgs = allMessages.stream()
            .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
            .collect(Collectors.toList());

        List<ShapedMessage> bookendStart = bookendSize > 0
            ? uaMsgs.stream().limit(bookendSize).map(m -> shapeMessage(m, null, 1200)).collect(Collectors.toList())
            : List.of();
        List<ShapedMessage> bookendEnd = bookendSize > 0
            ? uaMsgs.stream().skip(Math.max(0, uaMsgs.size() - bookendSize)).map(m -> shapeMessage(m, null, 1200)).collect(Collectors.toList())
            : List.of();

        return new AnchoredView(windowMsgs, messagesBefore, messagesAfter, bookendStart, bookendEnd);
    }

    // ── Helpers ──

    private UUID resolveLineageRoot(UUID sessionId) {
        if (sessionId == null) return null;
        List<UUID> lineage = sessionLineageService.findAncestorSessionIds(sessionId);
        return lineage.isEmpty() ? sessionId : lineage.get(0);
    }

    private boolean isSessionLeftLiveContext(UUID sessionId) {
        SessionEntity s = sessionRepository.findById(sessionId).orElse(null);
        if (s == null) return false;
        String endReason = s.getEndReason();
        return "compression".equals(endReason) || (endReason != null && FRESH_RESET_END_REASONS.contains(endReason));
    }

    private boolean isCompressionEnded(SessionEntity s) {
        return s != null && "compression".equals(s.getEndReason());
    }

    private ShapedMessage shapeMessage(MessageEntity m, UUID anchorId, int maxContentLen) {
        String content = m.getContent();
        // Strip ANSI escape sequences from recalled terminal output (Hermes parity)
        if (content != null && content.contains("\u001b")) {
            content = content.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        }
        boolean truncated = false;
        Integer originalChars = null;
        if (content != null && maxContentLen > 0 && content.length() > maxContentLen) {
            content = content.substring(0, maxContentLen) + "…";
            truncated = true;
            originalChars = m.getContent().length();
        }
        boolean isAnchor = anchorId != null && anchorId.equals(m.getId());
        return new ShapedMessage(m.getId(), m.getRole(), content,
            m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
            m.getToolCallName(), truncated, originalChars, isAnchor);
    }

    private List<ShapedMessage> filterCompaction(List<ShapedMessage> messages) {
        return messages.stream().filter(m -> !isCompactionSummary(m.content())).collect(Collectors.toList());
    }

    private boolean isCompactionSummary(String content) {
        if (content == null || content.isEmpty()) return false;
        String stripped = content.stripLeading();
        for (String prefix : COMPACTION_PREFIXES) {
            if (stripped.startsWith(prefix)) return true;
        }
        return false;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String sessionLink(UUID sessionId, String profile) {
        String name = (profile != null && !profile.isBlank()) ? profile.strip() : "default";
        return "@session:" + name + "/" + sessionId;
    }

    private String formatTimestamp(Instant instant) {
        if (instant == null) return "unknown";
        return DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a")
            .withZone(ZoneId.systemDefault()).format(instant);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> r = new ArrayList<>(a.size() + b.size());
        r.addAll(a); r.addAll(b); return r;
    }

    private DiscoverResult buildTitleResult(SessionEntity s, String linkProfile) {
        return new DiscoverResult(
            s.getId(), formatTimestamp(s.getCreatedAt()),
            s.getSource() != null ? s.getSource() : "unknown",
            s.getModelName() != null ? s.getModelName() : "unknown",
            s.getTitle(), null, null, "title match",
            List.of(), List.of(), List.of(), 0, 0, "full", null,
            sessionLink(s.getId(), linkProfile)
        );
    }

    // ── Result types ──

    /** Union result carrying data for any of the four modes. */
    public static final class SearchResult {
        final boolean success;
        final String mode;
        final String error;
        // Discovery
        final String query;
        final String detail;
        final List<DiscoverResult> discoverResults;
        // Scroll
        final UUID scrollSessionId;
        final UUID scrollAroundMessageId;
        final int scrollWindow;
        final AnchoredView scrollView;
        final Map<String, Object> scrollMeta;
        // Read
        final UUID readSessionId;
        final Map<String, Object> readMeta;
        final List<ShapedMessage> readMessages;
        final int readTotal;
        final boolean readTruncated;
        final int readHead;
        final int readTail;
        final String readLink;
        // Browse
        final List<BrowseResult> browseResults;

        private SearchResult(boolean success, String mode, String error, String query, String detail,
                              List<DiscoverResult> discoverResults,
                              UUID scrollSessionId, UUID scrollAroundMessageId, int scrollWindow,
                              AnchoredView scrollView, Map<String, Object> scrollMeta,
                              UUID readSessionId, Map<String, Object> readMeta,
                              List<ShapedMessage> readMessages, int readTotal, boolean readTruncated,
                              int readHead, int readTail, String readLink,
                              List<BrowseResult> browseResults) {
            this.success = success; this.mode = mode; this.error = error;
            this.query = query; this.detail = detail; this.discoverResults = discoverResults;
            this.scrollSessionId = scrollSessionId; this.scrollAroundMessageId = scrollAroundMessageId;
            this.scrollWindow = scrollWindow; this.scrollView = scrollView; this.scrollMeta = scrollMeta;
            this.readSessionId = readSessionId; this.readMeta = readMeta;
            this.readMessages = readMessages; this.readTotal = readTotal;
            this.readTruncated = readTruncated; this.readHead = readHead; this.readTail = readTail;
            this.readLink = readLink; this.browseResults = browseResults;
        }

        static SearchResult error(String error) {
            return new SearchResult(false, null, error, null, null, null, null, null, 0, null, null, null, null, null, 0, false, 0, 0, null, null);
        }
        static SearchResult discover(String query, String detail, List<DiscoverResult> results) {
            return new SearchResult(true, "discover", null, query, detail, results, null, null, 0, null, null, null, null, null, 0, false, 0, 0, null, null);
        }
        static SearchResult scroll(UUID sessionId, UUID aroundMessageId, int window, AnchoredView view, Map<String, Object> meta) {
            return new SearchResult(true, "scroll", null, null, null, null, sessionId, aroundMessageId, window, view, meta, null, null, null, 0, false, 0, 0, null, null);
        }
        static SearchResult read(UUID sessionId, Map<String, Object> meta, List<ShapedMessage> messages, int total, boolean truncated, int head, int tail, String link) {
            return new SearchResult(true, "read", null, null, null, null, null, null, 0, null, null, sessionId, meta, messages, total, truncated, head, tail, link, null);
        }
        static SearchResult browse(List<BrowseResult> results) {
            return new SearchResult(true, "browse", null, null, null, null, null, null, 0, null, null, null, null, null, 0, false, 0, 0, null, results);
        }
    }

    record DiscoverMatch(UUID rawSessionId, UUID lineageRoot, UUID messageId, String matchType) {}
    public record AnchoredView(List<ShapedMessage> window, int messagesBefore, int messagesAfter,
                                List<ShapedMessage> bookendStart, List<ShapedMessage> bookendEnd) {}
    public record ShapedMessage(
        UUID id, String role, String content, String timestamp,
        @com.fasterxml.jackson.annotation.JsonProperty("tool_name") String toolName,
        @com.fasterxml.jackson.annotation.JsonProperty("content_truncated") boolean contentTruncated,
        @com.fasterxml.jackson.annotation.JsonProperty("original_content_chars") Integer originalContentChars,
        boolean anchor) {}
    public record DiscoverResult(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") UUID sessionId,
        String when, String source, String model,
        String title,
        @com.fasterxml.jackson.annotation.JsonProperty("matched_role") String matchedRole,
        @com.fasterxml.jackson.annotation.JsonProperty("match_message_id") UUID matchMessageId,
        String snippet,
        @com.fasterxml.jackson.annotation.JsonProperty("bookend_start") List<ShapedMessage> bookendStart,
        List<ShapedMessage> messages,
        @com.fasterxml.jackson.annotation.JsonProperty("bookend_end") List<ShapedMessage> bookendEnd,
        @com.fasterxml.jackson.annotation.JsonProperty("messages_before") int messagesBefore,
        @com.fasterxml.jackson.annotation.JsonProperty("messages_after") int messagesAfter,
        String detail,
        @com.fasterxml.jackson.annotation.JsonProperty("parent_session_id") UUID parentSessionId,
        String link) {}
    public record BrowseResult(
        @com.fasterxml.jackson.annotation.JsonProperty("session_id") UUID sessionId,
        String link, String title, String source,
        @com.fasterxml.jackson.annotation.JsonProperty("started_at") String startedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("last_active") String lastActive,
        @com.fasterxml.jackson.annotation.JsonProperty("message_count") int messageCount,
        String preview) {}
}