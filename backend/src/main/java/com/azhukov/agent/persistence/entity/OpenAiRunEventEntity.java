package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Append-only run event with monotonic sequence for restart-safe SSE replay (WP-6, V59). */
@Entity
@Table(name = "openai_run_events")
@IdClass(OpenAiRunEventEntity.Pk.class)
@Data
public class OpenAiRunEventEntity {

    @Id
    @Column(name = "run_id", length = 64, insertable = false, updatable = false)
    private String runId;

    @Id
    @Column(name = "seq", insertable = false, updatable = false)
    private long seq;

    @Column(name = "event_json", nullable = false, columnDefinition = "TEXT")
    private String eventJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Data
    public static class Pk implements java.io.Serializable {
        private String runId;
        private long seq;
    }
}
