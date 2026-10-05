package com.azhukov.agent.bot.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Finds the locally accepted Telegram reviews that still require a backend acknowledgement.
 */
@Repository
public interface ReviewDeliveryReceiptRepository extends JpaRepository<ReviewDeliveryReceiptEntity, UUID> {

    Optional<ReviewDeliveryReceiptEntity> findByBackendSessionIdAndBackendDeliveryId(
        UUID backendSessionId, UUID backendDeliveryId);
}
