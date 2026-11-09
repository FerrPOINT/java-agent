package com.azhukov.agent.service;

import com.azhukov.agent.persistence.repository.DeliveryWorkItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * WP-1 (Hermes sweep_recoverable parity): periodic recovery sweep for the
 * durable delivery ledger. Claims whose consumer died between claim and
 * ack/release are returned to pending (bounded by the attempts cap) or
 * terminalized — a bot crash mid-delivery can never strand an item forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryLedgerSweeper {

    private final DeliveryWorkItemService deliveryService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 45_000L)
    public void sweep() {
        try {
            DeliveryWorkItemService.SweepResult result =
                deliveryService.sweepStaleClaims(Instant.now(), DeliveryWorkItemService.CLAIM_LEASE);
            if (result.recovered() > 0 || result.abandoned() > 0 || result.unknown() > 0) {
                log.info("Delivery ledger sweep: {} claims recovered, {} abandoned, {} unknown",
                    result.recovered(), result.abandoned(), result.unknown());
            }
        } catch (Exception e) {
            log.debug("Delivery ledger sweep failed: {}", e.getMessage());
        }
    }
}
