package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/**
 * B2.5 / C2: Code-based pairing auth for unknown users.
 * <p>
 * Generates 8-char codes from an unambiguous alphabet (no 0/O/1/I).
 * Codes expire after 1 hour. Max 3 pending codes per user/platform.
 * The bot owner approves via /approve.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PairingService {

    // Unambiguous alphabet: no 0/O/1/I
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CODE_LENGTH = 8;

    private final PairingCodeRepository repository;
    private final BotProperties properties;
    private final SecureRandom random = new SecureRandom();

    /**
     * Generate a pairing code for an unauthorized user.
     *
     * @param userId   Telegram user ID
     * @param chatId   Telegram chat ID
     * @param username Telegram username (may be null)
     * @return the generated code, or empty if pairing is disabled or max pending reached
     */
    @Transactional
    public Optional<String> generateCode(String userId, String chatId, String username) {
        if (!properties.getAuth().getPairing().isEnabled()) {
            return Optional.empty();
        }

        // Check max pending
        long pendingCount = repository.countByUserIdAndStatus(userId, "pending");
        int maxPending = properties.getAuth().getPairing().getMaxPending();
        if (pendingCount >= maxPending) {
            log.debug("Max pending pairing codes reached for userId={}", userId);
            return Optional.empty();
        }

        String code = generateCode();
        PairingCodeEntity entity = new PairingCodeEntity();
        entity.setCode(code);
        entity.setUserId(userId);
        entity.setChatId(chatId);
        entity.setUsername(username);
        entity.setStatus("pending");
        entity.setCreatedAt(Instant.now());
        int expiryHours = properties.getAuth().getPairing().getCodeExpiryHours();
        entity.setExpiresAt(Instant.now().plusSeconds(expiryHours * 3600L));
        repository.save(entity);

        log.info("Generated pairing code for userId={} chatId={}", userId, chatId);
        return Optional.of(code);
    }

    /**
     * Validate a pairing code (check if it exists, is pending, and not expired).
     *
     * @param code the 8-char pairing code
     * @return the entity if valid, empty otherwise
     */
    @Transactional
    public Optional<PairingCodeEntity> validateCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<PairingCodeEntity> entity = repository.findByCodeAndStatus(code, "pending");
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entity.get().getExpiresAt())) {
            // Expired — update status
            entity.get().setStatus("expired");
            repository.save(entity.get());
            return Optional.empty();
        }
        return entity;
    }

    /**
     * Approve a pairing code — marks it as approved.
     *
     * @param code the 8-char pairing code
     * @return the approved entity, or empty if not found/expired
     */
    @Transactional
    public Optional<PairingCodeEntity> approve(String code) {
        Optional<PairingCodeEntity> entity = validateCode(code);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        entity.get().setStatus("approved");
        repository.save(entity.get());
        log.info("Approved pairing code {} for userId={}", code, entity.get().getUserId());
        return entity;
    }

    /**
     * Deny a pairing code — marks it as denied.
     *
     * @param code the 8-char pairing code
     * @return true if denied, false if not found
     */
    @Transactional
    public boolean deny(String code) {
        Optional<PairingCodeEntity> entity = repository.findByCodeAndStatus(code, "pending");
        if (entity.isEmpty()) {
            return false;
        }
        entity.get().setStatus("denied");
        repository.save(entity.get());
        log.info("Denied pairing code {}", code);
        return true;
    }

    /**
     * Check whether a user has at least one approved pairing code.
     *
     * @param userId the Telegram user ID (as a string)
     * @return {@code true} if there is at least one pairing code with status "approved" for this user
     */
    @Transactional(readOnly = true)
    public boolean hasApprovedPairing(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return !repository.findByUserIdAndStatus(userId, "approved").isEmpty();
    }

    /**
     * Generate a random 8-char code from the unambiguous alphabet.
     */
    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}