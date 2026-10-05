package com.azhukov.agent.bot.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Local receipt prevents re-sending a review after an ambiguous backend acknowledgement. */
@Entity
@Table(name = "review_delivery_receipts")
@Data
public class ReviewDeliveryReceiptEntity {

    @Id
    private UUID id;

    @Column(name = "backend_session_id", nullable = false)
    private UUID backendSessionId;

    @Column(name = "backend_delivery_id", nullable = false)
    private UUID backendDeliveryId;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;
}
