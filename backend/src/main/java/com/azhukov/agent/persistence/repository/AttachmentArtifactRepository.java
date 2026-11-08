package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.AttachmentArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentArtifactRepository extends JpaRepository<AttachmentArtifactEntity, String> {

    /** Dedupe lookup: same owner + hash + disposition is ONE artifact. */
    Optional<AttachmentArtifactEntity> findFirstByOwnerIdAndContentHashAndDispositionAndSessionIdAndDedupScope(
        String ownerId, String contentHash, String disposition, UUID sessionId, String dedupScope);

    Optional<AttachmentArtifactEntity> findFirstByOwnerIdAndContentHashAndDisposition(
        String ownerId, String contentHash, String disposition);

    List<AttachmentArtifactEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<AttachmentArtifactEntity> findByStateAndExpiresAtBefore(String state, Instant cutoff);

    /** TTL sweep input: expired artifacts of ANY state (delivered ones expire too). */
    List<AttachmentArtifactEntity> findByExpiresAtBefore(Instant cutoff);

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE AttachmentArtifactEntity a SET a.outboundClaimedAt = :now
        WHERE a.id = :id AND a.ownerId = :owner AND a.sessionId = :session
          AND a.expiresAt > :now AND a.state = 'received' AND a.outboundClaimedAt IS NULL
        """)
    int claimOutbound(@Param("id") String id, @Param("owner") String owner,
                      @Param("session") UUID session, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM AttachmentArtifactEntity a WHERE a.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
