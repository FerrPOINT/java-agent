package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hermes {@code SessionDB.prune_sessions} parity over the fields Java actually
 * persists (WP-4.6). Unsupported Hermes filters (cwd_prefix, billing provider,
 * chat id/type, branch, token/cost/tool-call bounds) are REJECTED with an
 * explicit error instead of silently widening the match set — the caller sees
 * exactly which filter needs missing data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionPruneService {

    /** Hermes filters Java cannot honestly evaluate yet (no persisted fields). */
    public static final Set<String> UNSUPPORTED_FILTERS = Set.of(
        "cwd_prefix", "billing_provider", "chat_id", "chat_type",
        "branch", "min_tokens", "max_tokens", "min_cost", "max_cost",
        "min_tool_calls", "max_tool_calls");

    private static final Duration IMPLICIT_CUTOFF = Duration.ofDays(90);
    private static final int MAX_CANDIDATES = 5_000;

    private final SessionRepository sessionRepository;
    private final SessionQueryService sessionQueryService;

    public record PruneRequest(
        String profile,
        String source,
        String titleContains,
        String endReason,
        String userId,
        String modelContains,
        Instant startedBefore,
        Instant startedAfter,
        Instant olderThan,
        Integer minMessages,
        Integer maxMessages,
        boolean includeArchived,
        boolean dryRun) {

        public Map<String, Object> unsupportedRequested(Map<String, Object> raw) {
            Map<String, Object> unsupported = new LinkedHashMap<>();
            for (String filter : UNSUPPORTED_FILTERS) {
                if (raw != null && raw.containsKey(filter)
                    && raw.get(filter) != null && !String.valueOf(raw.get(filter)).isBlank()) {
                    unsupported.put(filter, raw.get(filter));
                }
            }
            return unsupported;
        }
    }

    public record PruneResult(List<Map<String, Object>> pruned, List<Map<String, Object>> skippedOpen,
                              int deletedMessages, boolean dryRun,
                              Map<String, Object> unsupportedFilters) {}

    @Transactional
    public PruneResult prune(PruneRequest request) {
        List<SessionEntity> candidates = sessionRepository.listEndedSessions(
            request.profile(), PageRequest.of(0, MAX_CANDIDATES));

        Instant effectiveCutoff = request.olderThan() != null
            ? request.olderThan()
            : Instant.now().minus(IMPLICIT_CUTOFF);

        List<SessionEntity> matching = new ArrayList<>();
        List<Map<String, Object>> skippedOpen = new ArrayList<>();
        for (SessionEntity candidate : candidates) {
            if (!request.includeArchived() && Boolean.TRUE.equals(candidate.getArchived())) {
                continue;
            }
            if (matches(candidate, request, effectiveCutoff)) {
                matching.add(candidate);
            }
        }

        List<Map<String, Object>> pruned = new ArrayList<>();
        int deletedMessages = 0;
        for (SessionEntity session : matching) {
            Map<String, Object> summary = sessionSummary(session);
            pruned.add(summary);
            if (!request.dryRun()) {
                int messages = candidateMessageCount(session);
                if (sessionQueryService.deleteSession(session.getId())) {
                    deletedMessages += messages;
                }
            }
        }
        return new PruneResult(pruned, skippedOpen, deletedMessages, request.dryRun(),
            request.unsupportedRequested(null));
    }

    private boolean matches(SessionEntity session, PruneRequest request, Instant cutoff) {
        if (session.getEndReason() == null) {
            return false; // prune only ended sessions
        }
        if (session.getLastActive() != null && session.getLastActive().isAfter(cutoff)
            && request.olderThan() == null) {
            return false;
        }
        if (request.source() != null && !request.source().equals(session.getSource())) {
            return false;
        }
        if (request.endReason() != null && !request.endReason().equals(session.getEndReason())) {
            return false;
        }
        if (request.userId() != null && !request.userId().equals(session.getUserId())) {
            return false;
        }
        if (request.titleContains() != null && (session.getTitle() == null
            || !session.getTitle().toLowerCase().contains(request.titleContains().toLowerCase()))) {
            return false;
        }
        if (request.modelContains() != null && (session.getModelName() == null
            || !session.getModelName().toLowerCase().contains(request.modelContains().toLowerCase()))) {
            return false;
        }
        if (request.startedBefore() != null
            && (session.getCreatedAt() == null || !session.getCreatedAt().isBefore(request.startedBefore()))) {
            return false;
        }
        if (request.startedAfter() != null
            && (session.getCreatedAt() == null || !session.getCreatedAt().isAfter(request.startedAfter()))) {
            return false;
        }
        int messages = session.getMessageCount() == null ? 0 : session.getMessageCount();
        if (request.minMessages() != null && messages < request.minMessages()) {
            return false;
        }
        if (request.maxMessages() != null && messages > request.maxMessages()) {
            return false;
        }
        return true;
    }

    private int candidateMessageCount(SessionEntity session) {
        return session.getMessageCount() == null ? 0 : session.getMessageCount();
    }

    private Map<String, Object> sessionSummary(SessionEntity session) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", session.getId().toString());
        summary.put("title", session.getTitle());
        summary.put("end_reason", session.getEndReason());
        summary.put("message_count", session.getMessageCount());
        summary.put("last_active", session.getLastActive() == null ? null : session.getLastActive().toString());
        return summary;
    }
}
