package com.azhukov.agent.service;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.AgentUserEntity;
import com.azhukov.agent.persistence.entity.UserApiKeyEntity;
import com.azhukov.agent.persistence.repository.AgentUserRepository;
import com.azhukov.agent.persistence.repository.UserApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Creates users/API keys and resolves a presented API key to an owned identity. */
@Service
@RequiredArgsConstructor
public class UserAccessService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentUserRepository userRepository;
    private final UserApiKeyRepository apiKeyRepository;

    @Transactional
    public AgentUserEntity createUser(String username, String displayName, String role) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        String normalizedRole = UserContext.ROLE_ADMIN.equals(role)
            ? UserContext.ROLE_ADMIN : UserContext.ROLE_USER;
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
        AgentUserEntity user = new AgentUserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setRole(normalizedRole);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    /**
     * Creates a raw API key once. Only the SHA-256 digest is persisted.
     */
    @Transactional
    public IssuedApiKey issueApiKey(String userId, String label) {
        AgentUserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String rawKey = "agk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        UserApiKeyEntity key = new UserApiKeyEntity();
        key.setUserId(user.getId());
        key.setKeyHash(sha256(rawKey));
        key.setLabel(label);
        key.setCreatedAt(Instant.now());
        apiKeyRepository.save(key);
        return new IssuedApiKey(key.getId(), rawKey, user.getId(), user.getRole());
    }

    /** Resolve an API key by its digest without retaining the raw value. */
    @Transactional
    public AuthenticatedUser authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return null;
        return apiKeyRepository.findByKeyHash(sha256(rawKey))
            .flatMap(key -> userRepository.findById(key.getUserId()).map(user -> {
                key.setLastUsedAt(Instant.now());
                apiKeyRepository.save(key);
                return new AuthenticatedUser(user.getId(), user.getRole());
            }))
            .orElse(null);
    }

    public AgentUserEntity getUser(String userId) {
        return userRepository.findById(userId).orElse(null);
    }

    /** Lists all users (admin surface). */
    public List<AgentUserEntity> listUsers() {
        return userRepository.findAll();
    }

    /** Deletes an API key by id — revokes access immediately (hash is gone). */
    @Transactional
    public boolean revokeApiKey(UUID keyId) {
        if (apiKeyRepository.existsById(keyId)) {
            apiKeyRepository.deleteById(keyId);
            return true;
        }
        return false;
    }

    /** Lists API keys of a user; hashes are never exposed. */
    public List<UserApiKeyEntity> listApiKeys(String userId) {
        return apiKeyRepository.findAll().stream()
            .filter(k -> userId.equals(k.getUserId()))
            .toList();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record AuthenticatedUser(String userId, String role) {}
    public record IssuedApiKey(UUID id, String rawKey, String userId, String role) {}
}