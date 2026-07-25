package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId;
    private String actor;
    private String action;
    private String resource;
    private String details;

    @CreationTimestamp
    private Instant createdAt;

    public AuditLogEntity() {}

    public AuditLogEntity(String sessionId, String actor, String action, String resource, String details) {
        this.sessionId = sessionId;
        this.actor = actor;
        this.action = action;
        this.resource = resource;
        this.details = details;
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResource() { return resource; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
