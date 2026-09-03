package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.transaction.Transactional;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {
    SessionEntity findByUserId(String userId);

    List<SessionEntity> findAllByUserId(String userId);

    // M15: Count query for reliable has_more pagination
    long countByUserId(String userId);

    Page<SessionEntity> findAllByUserId(String userId, Pageable pageable);

    @Query(value = "SELECT id FROM sessions WHERE id IN (:ids)", nativeQuery = true)
    List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("UPDATE SessionEntity s SET s.parentSessionId = null WHERE s.parentSessionId IN :ids")
    int orphanChildrenOf(@Param("ids") Collection<UUID> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SessionEntity s SET s.profile = :profile WHERE s.profile IS NULL OR TRIM(s.profile) = ''")
    int backfillBlankProfiles(@Param("profile") String profile);

    @Query(value = """
        SELECT id FROM sessions s
        WHERE COALESCE(message_count, 0) = 0
          AND end_reason IS NOT NULL
          AND COALESCE(archived, FALSE) = FALSE
          AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id)
        """, nativeQuery = true)
    List<UUID> findEmptyEndedUnarchivedIds();

    @Query(value = """
        SELECT COUNT(*) FROM sessions s
        WHERE COALESCE(message_count, 0) = 0
          AND end_reason IS NOT NULL
          AND COALESCE(archived, FALSE) = FALSE
          AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id)
        """, nativeQuery = true)
    long countEmptyEndedUnarchived();

    @Query(value = "SELECT COUNT(*) FROM sessions WHERE COALESCE(archived, FALSE) = FALSE", nativeQuery = true)
    long countUnarchivedSessions();

    @Query(value = "SELECT COUNT(*) FROM sessions WHERE COALESCE(archived, FALSE) = TRUE", nativeQuery = true)
    long countArchivedSessions();

    @Query(value = """
        SELECT COALESCE(source, ''), COUNT(*)
        FROM sessions
        WHERE parent_session_id IS NULL
        GROUP BY COALESCE(source, '')
        """, nativeQuery = true)
    List<Object[]> countTopLevelSessionsBySource();

    @Query(value = """
        SELECT COUNT(*) FROM sessions
        WHERE user_id = :userId
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        """, nativeQuery = true)
    long countVisibleByUserId(
        @Param("userId") String userId,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT COUNT(*) FROM sessions
        WHERE user_id = :userId
          AND COALESCE(profile, 'default') = :profile
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        """, nativeQuery = true)
    long countVisibleByUserIdAndProfile(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findPageByUserIdOrderByRecent(
        @Param("userId") String userId,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(profile, 'default') = :profile
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findPageByUserIdAndProfileOrderByRecent(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,
            created_at DESC,
            updated_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findPageByUserIdOrderByCreated(
        @Param("userId") String userId,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(profile, 'default') = :profile
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:excludePinned = FALSE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,
            created_at DESC,
            updated_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findPageByUserIdAndProfileOrderByCreated(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren,
        @Param("excludePinned") boolean excludePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(pinned, FALSE) = TRUE
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            id ASC
        """, nativeQuery = true)
    List<SessionEntity> findPinnedByUserIdOrderByRecent(
        @Param("userId") String userId,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(profile, 'default') = :profile
          AND COALESCE(pinned, FALSE) = TRUE
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            id ASC
        """, nativeQuery = true)
    List<SessionEntity> findPinnedByUserIdAndProfileOrderByRecent(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(pinned, FALSE) = TRUE
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
        ORDER BY
            CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,
            created_at DESC,
            updated_at DESC,
            id ASC
        """, nativeQuery = true)
    List<SessionEntity> findPinnedByUserIdOrderByCreated(
        @Param("userId") String userId,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND COALESCE(profile, 'default') = :profile
          AND COALESCE(pinned, FALSE) = TRUE
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:title IS NULL OR TRIM(title) = :title)
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
        ORDER BY
            CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,
            created_at DESC,
            updated_at DESC,
            id ASC
        """, nativeQuery = true)
    List<SessionEntity> findPinnedByUserIdAndProfileOrderByCreated(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("title") String title,
        @Param("includeChildren") boolean includeChildren);

    @Query(value = """
        SELECT COUNT(*) FROM sessions
        WHERE user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(profile), ''), 'default') = :profile)
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:sourcesEmpty = TRUE OR source IN (:sources))
          AND (:excludeSourcesEmpty = TRUE OR source IS NULL OR source NOT IN (:excludeSources))
          AND COALESCE(message_count, 0) >= :minMessageCount
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:includePinned = TRUE OR COALESCE(pinned, FALSE) = FALSE)
        """, nativeQuery = true)
    long countProfileDashboardSessions(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("sourcesEmpty") boolean sourcesEmpty,
        @Param("sources") Collection<String> sources,
        @Param("excludeSourcesEmpty") boolean excludeSourcesEmpty,
        @Param("excludeSources") Collection<String> excludeSources,
        @Param("minMessageCount") int minMessageCount,
        @Param("includeChildren") boolean includeChildren,
        @Param("includePinned") boolean includePinned);

    @Query(value = """
        SELECT COALESCE(NULLIF(TRIM(profile), ''), 'default') AS profile_name, COUNT(*) AS total
        FROM sessions
        WHERE user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(profile), ''), 'default') = :profile)
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:sourcesEmpty = TRUE OR source IN (:sources))
          AND (:excludeSourcesEmpty = TRUE OR source IS NULL OR source NOT IN (:excludeSources))
          AND COALESCE(message_count, 0) >= :minMessageCount
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:includePinned = TRUE OR COALESCE(pinned, FALSE) = FALSE)
        GROUP BY COALESCE(NULLIF(TRIM(profile), ''), 'default')
        ORDER BY profile_name ASC
        """, nativeQuery = true)
    List<Object[]> countProfileDashboardSessionsByProfile(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("sourcesEmpty") boolean sourcesEmpty,
        @Param("sources") Collection<String> sources,
        @Param("excludeSourcesEmpty") boolean excludeSourcesEmpty,
        @Param("excludeSources") Collection<String> excludeSources,
        @Param("minMessageCount") int minMessageCount,
        @Param("includeChildren") boolean includeChildren,
        @Param("includePinned") boolean includePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(profile), ''), 'default') = :profile)
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:sourcesEmpty = TRUE OR source IN (:sources))
          AND (:excludeSourcesEmpty = TRUE OR source IS NULL OR source NOT IN (:excludeSources))
          AND COALESCE(message_count, 0) >= :minMessageCount
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:includePinned = TRUE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            created_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findProfileDashboardPageOrderByRecent(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("sourcesEmpty") boolean sourcesEmpty,
        @Param("sources") Collection<String> sources,
        @Param("excludeSourcesEmpty") boolean excludeSourcesEmpty,
        @Param("excludeSources") Collection<String> excludeSources,
        @Param("minMessageCount") int minMessageCount,
        @Param("includeChildren") boolean includeChildren,
        @Param("includePinned") boolean includePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(profile), ''), 'default') = :profile)
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:sourcesEmpty = TRUE OR source IN (:sources))
          AND (:excludeSourcesEmpty = TRUE OR source IS NULL OR source NOT IN (:excludeSources))
          AND COALESCE(message_count, 0) >= :minMessageCount
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
          AND (:includePinned = TRUE OR COALESCE(pinned, FALSE) = FALSE)
        ORDER BY
            CASE WHEN created_at IS NULL THEN 1 ELSE 0 END,
            created_at DESC,
            updated_at DESC,
            id ASC
        LIMIT :limit OFFSET :offset
        """, nativeQuery = true)
    List<SessionEntity> findProfileDashboardPageOrderByCreated(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("limit") int limit,
        @Param("offset") int offset,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("sourcesEmpty") boolean sourcesEmpty,
        @Param("sources") Collection<String> sources,
        @Param("excludeSourcesEmpty") boolean excludeSourcesEmpty,
        @Param("excludeSources") Collection<String> excludeSources,
        @Param("minMessageCount") int minMessageCount,
        @Param("includeChildren") boolean includeChildren,
        @Param("includePinned") boolean includePinned);

    @Query(value = """
        SELECT * FROM sessions
        WHERE user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(profile), ''), 'default') = :profile)
          AND COALESCE(pinned, FALSE) = TRUE
          AND (:includeArchived = TRUE OR COALESCE(archived, FALSE) = FALSE)
          AND (:archivedOnly = FALSE OR COALESCE(archived, FALSE) = TRUE)
          AND (:includeHidden = TRUE OR COALESCE(hidden, FALSE) = FALSE)
          AND (:source IS NULL OR source = :source)
          AND (:sourcesEmpty = TRUE OR source IN (:sources))
          AND (:excludeSourcesEmpty = TRUE OR source IS NULL OR source NOT IN (:excludeSources))
          AND COALESCE(message_count, 0) >= :minMessageCount
          AND (:includeChildren = TRUE OR parent_session_id IS NULL)
        ORDER BY
            CASE WHEN last_active IS NULL THEN 1 ELSE 0 END,
            last_active DESC,
            updated_at DESC,
            created_at DESC,
            id ASC
        """, nativeQuery = true)
    List<SessionEntity> findProfileDashboardPinnedOrderByRecent(
        @Param("userId") String userId,
        @Param("profile") String profile,
        @Param("includeArchived") boolean includeArchived,
        @Param("archivedOnly") boolean archivedOnly,
        @Param("includeHidden") boolean includeHidden,
        @Param("source") String source,
        @Param("sourcesEmpty") boolean sourcesEmpty,
        @Param("sources") Collection<String> sources,
        @Param("excludeSourcesEmpty") boolean excludeSourcesEmpty,
        @Param("excludeSources") Collection<String> excludeSources,
        @Param("minMessageCount") int minMessageCount,
        @Param("includeChildren") boolean includeChildren);

    @Query(value = """
        SELECT COALESCE(NULLIF(TRIM(s.profile), ''), 'default') AS profile_name,
               COALESCE(SUM(COALESCE(u.total_tokens, 0)), 0) AS total_tokens,
               COALESCE(SUM(COALESCE(u.cost, 0)), 0) AS total_cost
        FROM sessions s
        LEFT JOIN usage_log u ON u.session_id = s.id
        WHERE s.user_id = :userId
          AND (:profile IS NULL OR COALESCE(NULLIF(TRIM(s.profile), ''), 'default') = :profile)
        GROUP BY COALESCE(NULLIF(TRIM(s.profile), ''), 'default')
        ORDER BY profile_name ASC
        """, nativeQuery = true)
    List<Object[]> countProfileDashboardUsageByProfile(
        @Param("userId") String userId,
        @Param("profile") String profile);

    @Query(value = """
        SELECT * FROM sessions s
        WHERE COALESCE(NULLIF(TRIM(s.profile), ''), 'default') = :profile
          AND (s.end_reason IS NOT NULL OR LOWER(COALESCE(s.session_status, 'active')) <> 'active')
          AND (:lastActiveBefore IS NULL OR COALESCE(
               (SELECT MAX(m.created_at) FROM messages m WHERE m.session_id = s.id),
               s.last_active, s.updated_at, s.created_at) < :lastActiveBefore)
          AND (:startedBefore IS NULL OR s.created_at < :startedBefore)
          AND (:startedAfter IS NULL OR s.created_at >= :startedAfter)
          AND (:source IS NULL OR s.source = :source)
          AND (:titleLike IS NULL OR LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :titleLike, '%')))
          AND (:endReason IS NULL OR s.end_reason = :endReason)
          AND (:userId IS NULL OR s.user_id = :userId)
          AND (:minMessages IS NULL OR COALESCE(s.message_count, 0) >= :minMessages)
          AND (:maxMessages IS NULL OR COALESCE(s.message_count, 0) <= :maxMessages)
          AND (:modelLike IS NULL OR LOWER(COALESCE(s.model_name, '')) LIKE LOWER(CONCAT('%', :modelLike, '%')))
          AND (:includeArchived = TRUE OR COALESCE(s.archived, FALSE) = FALSE)
          AND COALESCE(s.pinned, FALSE) = FALSE
        ORDER BY COALESCE(
               (SELECT MAX(m.created_at) FROM messages m WHERE m.session_id = s.id),
               s.last_active, s.updated_at, s.created_at) ASC,
               s.created_at ASC,
               s.id ASC
        """, nativeQuery = true)
    List<SessionEntity> findPruneCandidates(
        @Param("profile") String profile,
        @Param("lastActiveBefore") Instant lastActiveBefore,
        @Param("startedBefore") Instant startedBefore,
        @Param("startedAfter") Instant startedAfter,
        @Param("source") String source,
        @Param("titleLike") String titleLike,
        @Param("endReason") String endReason,
        @Param("userId") String userId,
        @Param("minMessages") Integer minMessages,
        @Param("maxMessages") Integer maxMessages,
        @Param("modelLike") String modelLike,
        @Param("includeArchived") boolean includeArchived);

    @Query(value = """
        SELECT COUNT(*) FROM sessions s
        WHERE COALESCE(NULLIF(TRIM(s.profile), ''), 'default') = :profile
          AND s.end_reason IS NULL
          AND LOWER(COALESCE(s.session_status, 'active')) = 'active'
          AND (:lastActiveBefore IS NULL OR COALESCE(
               (SELECT MAX(m.created_at) FROM messages m WHERE m.session_id = s.id),
               s.last_active, s.updated_at, s.created_at) < :lastActiveBefore)
          AND (:startedBefore IS NULL OR s.created_at < :startedBefore)
          AND (:startedAfter IS NULL OR s.created_at >= :startedAfter)
          AND (:source IS NULL OR s.source = :source)
          AND (:titleLike IS NULL OR LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :titleLike, '%')))
          AND (:endReason IS NULL OR s.end_reason = :endReason)
          AND (:userId IS NULL OR s.user_id = :userId)
          AND (:minMessages IS NULL OR COALESCE(s.message_count, 0) >= :minMessages)
          AND (:maxMessages IS NULL OR COALESCE(s.message_count, 0) <= :maxMessages)
          AND (:modelLike IS NULL OR LOWER(COALESCE(s.model_name, '')) LIKE LOWER(CONCAT('%', :modelLike, '%')))
          AND (:includeArchived = TRUE OR COALESCE(s.archived, FALSE) = FALSE)
          AND COALESCE(s.pinned, FALSE) = FALSE
        """, nativeQuery = true)
    long countOpenPruneMatches(
        @Param("profile") String profile,
        @Param("lastActiveBefore") Instant lastActiveBefore,
        @Param("startedBefore") Instant startedBefore,
        @Param("startedAfter") Instant startedAfter,
        @Param("source") String source,
        @Param("titleLike") String titleLike,
        @Param("endReason") String endReason,
        @Param("userId") String userId,
        @Param("minMessages") Integer minMessages,
        @Param("maxMessages") Integer maxMessages,
        @Param("modelLike") String modelLike,
        @Param("includeArchived") boolean includeArchived);

    List<SessionEntity> findByTitleContainingIgnoreCase(String title);

    /**
     * Find child sessions by parent_session_id, ordered by most recently created.
     * Used for compression child-chain resolution (parity with Hermes resolve_resume_session_id).
     */
    List<SessionEntity> findByParentSessionIdOrderByCreatedAtDesc(UUID parentSessionId);

    /**
     * List recent sessions excluding hidden sources, ordered by last_active desc.
     * Mirrors Hermes list_sessions_rich(exclude_sources=..., order_by_last_active=True).
     */
    @Query("SELECT s FROM SessionEntity s WHERE (s.source IS NULL OR s.source NOT IN :excludedSources) ORDER BY s.lastActive DESC NULLS LAST, s.updatedAt DESC")
    List<SessionEntity> listRecentExcludingSources(@Param("excludedSources") List<String> excludedSources, Pageable pageable);

    /**
     * Full-text search on session titles using PostgreSQL tsvector.
     * Falls back to LIKE if FTS is not available (H2).
     */
    @Query(value = "SELECT * FROM sessions WHERE title_tsv @@ plainto_tsquery('english', :q) " +
                   "ORDER BY ts_rank(title_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<SessionEntity> searchByTitleFts(@Param("q") String query);

    /**
     * Full-text search on session titles excluding hidden sources.
     */
    @Query(value = "SELECT * FROM sessions WHERE title_tsv @@ plainto_tsquery('english', :q) " +
                   "AND (source IS NULL OR source NOT IN :excludedSources) " +
                   "ORDER BY ts_rank(title_tsv, plainto_tsquery('english', :q)) DESC",
           nativeQuery = true)
    List<SessionEntity> searchByTitleFtsExcludingSources(@Param("q") String query, @Param("excludedSources") List<String> excludedSources);

    /**
     * Find session by title (exact match, case-insensitive) — for title-match discovery.
     */
    SessionEntity findByTitleIgnoreCase(String title);

    SessionEntity findByTitle(String title);

    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.updatedAt = :updatedAt WHERE s.id = :id")
    void touchUpdatedAt(UUID id, Instant updatedAt);

    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.lastActive = :lastActive, s.messageCount = :messageCount WHERE s.id = :id")
    void updateLastActiveAndMessageCount(UUID id, Instant lastActive, int messageCount);

    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.lastActive = :lastActive, s.messageCount = "
        + "COALESCE(s.messageCount, 0) + :delta WHERE s.id = :id")
    void incrementMessageCount(UUID id, Instant lastActive, int delta);

    @Modifying
    @Transactional
    @Query("UPDATE SessionEntity s SET s.preview = :preview WHERE s.id = :id")
    void updatePreview(UUID id, String preview);
}
