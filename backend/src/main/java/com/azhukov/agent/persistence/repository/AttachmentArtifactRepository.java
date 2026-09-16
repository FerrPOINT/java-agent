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
    Optional<AttachmentArtifactEntity> findFirstByOwnerIdAndContentHashAndDisposition(
        String ownerId, String contentHash, String disposition);

    List<AttachmentArtifactEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<AttachmentArtifactEntity> findByStateAndExpiresAtBefore(String state, Instant cutoff);

    /** TTL sweep input: expired artifacts of ANY state (delivered ones expire too). */
    List<AttachmentArtifactEntity> findByExpiresAtBefore(Instant cutoff);

    @Modifying
    @Query("DELETE FROM AttachmentArtifactEntity a WHERE a.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
