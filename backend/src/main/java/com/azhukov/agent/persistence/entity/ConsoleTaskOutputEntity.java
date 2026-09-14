package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/** Redacted console task output line with monotonic sequence (WP-9, V61). */
@Entity
@Table(name = "console_task_output")
@IdClass(ConsoleTaskOutputEntity.Pk.class)
@Data
public class ConsoleTaskOutputEntity {

    @Id
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Id
    @Column(name = "sequence")
    private long sequence;

    @Column(name = "line", nullable = false, columnDefinition = "TEXT")
    private String line;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Data
    public static class Pk implements java.io.Serializable {
        private String taskId;
        private long sequence;
    }
}
