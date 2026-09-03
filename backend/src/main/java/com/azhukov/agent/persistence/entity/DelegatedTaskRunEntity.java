package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable ledger for Hermes-style background delegate_task runs.
 */
@Entity
@Table(name = "delegated_task_runs")
public class DelegatedTaskRunEntity {

    @Id
    private UUID id;

    @Column(name = "parent_session_id", nullable = false)
    private UUID parentSessionId;

    @Column(name = "child_session_id")
    private UUID childSessionId;

    @Column(length = 128)
    private String profile;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivery_dropped_at")
    private Instant deliveryDroppedAt;

    @Column(name = "delivery_target", length = 128)
    private String deliveryTarget;

    @Column(name = "delivery_error", columnDefinition = "text")
    private String deliveryError;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "delivery_idempotency_key", length = 128)
    private String deliveryIdempotencyKey;

    @Column(name = "delivery_claim", length = 160)
    private String deliveryClaim;

    @Column(name = "delivery_claimed_at")
    private Instant deliveryClaimedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null || status.isBlank()) {
            status = "running";
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(UUID parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public UUID getChildSessionId() {
        return childSessionId;
    }

    public void setChildSessionId(UUID childSessionId) {
        this.childSessionId = childSessionId;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public void setCancelRequestedAt(Instant cancelRequestedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getDeliveryDroppedAt() {
        return deliveryDroppedAt;
    }

    public void setDeliveryDroppedAt(Instant deliveryDroppedAt) {
        this.deliveryDroppedAt = deliveryDroppedAt;
    }

    public String getDeliveryTarget() {
        return deliveryTarget;
    }

    public void setDeliveryTarget(String deliveryTarget) {
        this.deliveryTarget = deliveryTarget;
    }

    public String getDeliveryError() {
        return deliveryError;
    }

    public void setDeliveryError(String deliveryError) {
        this.deliveryError = deliveryError;
    }

    public int getDeliveryAttempts() {
        return deliveryAttempts;
    }

    public void setDeliveryAttempts(int deliveryAttempts) {
        this.deliveryAttempts = deliveryAttempts;
    }

    public String getDeliveryIdempotencyKey() {
        return deliveryIdempotencyKey;
    }

    public void setDeliveryIdempotencyKey(String deliveryIdempotencyKey) {
        this.deliveryIdempotencyKey = deliveryIdempotencyKey;
    }

    public String getDeliveryClaim() {
        return deliveryClaim;
    }

    public void setDeliveryClaim(String deliveryClaim) {
        this.deliveryClaim = deliveryClaim;
    }

    public Instant getDeliveryClaimedAt() {
        return deliveryClaimedAt;
    }

    public void setDeliveryClaimedAt(Instant deliveryClaimedAt) {
        this.deliveryClaimedAt = deliveryClaimedAt;
    }
}
