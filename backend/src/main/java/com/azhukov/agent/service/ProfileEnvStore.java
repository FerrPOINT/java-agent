package com.azhukov.agent.service;

import com.azhukov.agent.core.security.SecureFileAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Profile {@code .env} store (ADR-013, WP-4). Allowlisted keys only, values
 * persisted with owner-only POSIX permissions under the profile directory,
 * masked reads. There is deliberately NO reveal API: reads expose
 * {@code is_set} + redacted preview only. When the filesystem cannot provide a
 * secure store (read-only FS, permissions cannot be restricted), writes fail
 * closed instead of writing plaintext.
 */
@Service
@RequiredArgsConstructor
public class ProfileEnvStore {

    /** Keys the dashboard may write — mirrors the envRows() catalog. */
    private static final Set<String> ALLOWED_KEYS = Set.of(
        "AGENT_MODEL_API_KEY",
        "AGENT_FLEET_PROFILE_TOKEN_SHA256",
        "AGENT_MODEL_BASE_URL",
        "AGENT_WEB_SEARXNG_URL",
        "AGENT_TTS_API_KEY",
        "AGENT_IMAGE_GEN_API_KEY",
        "AGENT_VISION_API_KEY",
        "AGENT_TRANSCRIPTION_API_KEY");

    private static final String REDACTED = "********";

    private final ObjectProvider<ProfileService> profileServiceProvider;

    /** Masked status rows for the whole allowlist. */
    public Map<String, Map<String, Object>> maskedRows(String profile) throws IOException {
        Map<String, String> values = readAll(profile);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (String key : ALLOWED_KEYS.stream().sorted().toList()) {
            String value = values.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("is_set", value != null && !value.isBlank());
            row.put("redacted_value", value == null || value.isBlank() ? null : REDACTED);
            row.put("is_password", key.endsWith("_API_KEY") || key.endsWith("_KEY") || key.endsWith("_SHA256"));
            rows.put(key, row);
        }
        return rows;
    }

    /** True when the dashboard may write this key. */
    public boolean isAllowedKey(String key) {
        return key != null && ALLOWED_KEYS.contains(key);
    }

    /** Set one allowlisted key. Non-allowlisted keys are rejected. */
    public synchronized void set(String profile, String key, String value) throws IOException {
        requireAllowlisted(key);
        Map<String, String> values = readAll(profile);
        String clean = value == null ? "" : value;
        if ("AGENT_FLEET_PROFILE_TOKEN_SHA256".equals(key) && !clean.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Fleet profile token fingerprint must be a SHA-256 digest");
        }
        if (java.util.regex.Pattern.compile("\\R").matcher(clean).find()) {
            throw new IllegalArgumentException("Environment values must fit on one line");
        }
        values.put(key, clean);
        writeAll(envPath(profiles().normalizeProfileName(profile)), values);
    }

    /** Delete one allowlisted key (missing key is a no-op). */
    public synchronized boolean delete(String profile, String key) throws IOException {
        requireAllowlisted(key);
        Map<String, String> values = readAll(profile);
        boolean removed = values.remove(key) != null;
        if (removed) {
            writeAll(envPath(profiles().normalizeProfileName(profile)), values);
        }
        return removed;
    }

    /** Raw values for internal readers (binder/bootstrap), never for API responses. */
    public Map<String, String> readAll(String profile) throws IOException {
        ProfileService profiles = profiles();
        String canon = profiles.normalizeProfileName(profile);
        Path envPath = envPath(canon);
        String raw;
        try {
            raw = SecureFileAccess.readUtf8(envPath);
        } catch (java.nio.file.NoSuchFileException e) {
            return new LinkedHashMap<>();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.startsWith("#")) {
                continue;
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    private void requireAllowlisted(String key) {
        if (key == null || !ALLOWED_KEYS.contains(key)) {
            throw new IllegalArgumentException("env key is not allowlisted: " + key);
        }
    }

    private Path envPath(String canon) throws IOException {
        var profiles = profiles();
        profiles.validateProfileName(canon);
        profiles.requireKnownProfile(canon);
        return profiles.profilesRoot().resolve(canon).resolve(".env");
    }

    private void writeAll(Path envPath, Map<String, String> values) throws IOException {
        StringBuilder sb = new StringBuilder("# managed by dashboard\n");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=')
                .append(entry.getValue())
                .append('\n');
        }
        try (var directory = SecureFileAccess.openOrCreateDirectory(envPath.getParent())) {
            directory.replacePrivateFile(envPath.getFileName(), sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private ProfileService profiles() throws IOException {
        ProfileService profiles = profileServiceProvider == null
            ? null : profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            throw new IOException("profile service is unavailable");
        }
        return profiles;
    }
}
