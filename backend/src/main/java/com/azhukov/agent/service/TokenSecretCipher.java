package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM envelope for MCP OAuth tokens and PKCE verifiers (WP-3, V58).
 * Key derivation: SHA-256 over a configured master secret; key version 1.
 * Ciphertext format: base64(iv[12] || ciphertext || tag[16]).
 *
 * <p>Fail-closed: without a configured master secret every encrypt/decrypt
 * call throws — no plaintext fallback.
 */
@Component
@ConditionalOnProperty("agent.mcp.oauth.encryption-secret")
@Slf4j
public class TokenSecretCipher {

    public static final int KEY_VERSION = 1;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenSecretCipher(@org.springframework.beans.factory.annotation.Value(
        "${agent.mcp.oauth.encryption-secret}") String masterSecret) {
        if (masterSecret == null || masterSecret.isBlank()) {
            throw new IllegalStateException(
                "agent.mcp.oauth.encryption-secret must be configured to store MCP OAuth tokens");
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                .digest(masterSecret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive MCP token encryption key", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] envelope = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(envelope, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, IV_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("token decryption failed", e);
        }
    }
}
