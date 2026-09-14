package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Append-only profile config revision audit row (ADR-013, WP-4). */
@Entity
@Table(name = "profile_config_revisions")
@Data
public class ProfileConfigRevisionEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "profile", nullable = false, length = 64)
    private String profile;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "actor", length = 128)
    private String actor;

    /** Hash of the written config — never raw secret content. */
    @Column(name = "summary_hash", nullable = false, length = 128)
    private String summaryHash;

    /** applied | failed | rolled_back. */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "applied";

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
