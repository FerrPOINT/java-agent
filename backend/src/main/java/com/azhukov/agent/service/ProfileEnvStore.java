package com.azhukov.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
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
@Slf4j
public class ProfileEnvStore {

    /** Keys the dashboard may write — mirrors the envRows() catalog. */
    private static final Set<String> ALLOWED_KEYS = Set.of(
        "AGENT_MODEL_API_KEY",
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
            row.put("is_password", key.endsWith("_API_KEY") || key.endsWith("_KEY"));
            rows.put(key, row);
        }
        return rows;
    }

    /** Set one allowlisted key. Non-allowlisted keys are rejected. */
    public void set(String profile, String key, String value) throws IOException {
        requireAllowlisted(key);
        Path envPath = secureEnvPath(profile);
        Map<String, String> values = readAll(profile);
        values.put(key, value == null ? "" : value);
        writeAll(envPath, values);
    }

    /** Delete one allowlisted key (missing key is a no-op). */
    public boolean delete(String profile, String key) throws IOException {
        requireAllowlisted(key);
        Map<String, String> values = readAll(profile);
        boolean removed = values.remove(key) != null;
        if (removed) {
            writeAll(secureEnvPath(profile), values);
        }
        return removed;
    }

    /** Raw values for internal readers (binder/bootstrap), never for API responses. */
    public Map<String, String> readAll(String profile) throws IOException {
        ProfileService profiles = profiles();
        String canon = profiles.normalizeProfileName(profile);
        Path envPath = envPath(canon);
        if (!Files.isRegularFile(envPath)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
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
        return profiles().profilesRoot().resolve(canon).resolve(".env");
    }

    /**
     * The secure-store contract: the .env file must live under the profile dir
     * with owner-only permissions. If we cannot guarantee that, fail closed.
     */
    private Path secureEnvPath(String profile) throws IOException {
        ProfileService profiles = profiles();
        String canon = profiles.normalizeProfileName(profile);
        Path envPath = envPath(canon);
        Files.createDirectories(envPath.getParent());
        if (!Files.isRegularFile(envPath)) {
            Files.writeString(envPath, "# managed by dashboard\n", StandardCharsets.UTF_8);
        }
        try {
            Files.setPosixFilePermissions(envPath, PosixFilePermissionsFromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            throw new IOException("secure env store unavailable: POSIX permissions not supported");
        }
        return envPath;
    }

    static Set<PosixFilePermission> PosixFilePermissionsFromString(String value) {
        Set<PosixFilePermission> result = new java.util.LinkedHashSet<>();
        int idx = 0;
        for (PosixFilePermission permission : List.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)) {
            char flag = value.charAt(idx++);
            if (flag != '-') {
                result.add(permission);
            }
        }
        return result;
    }

    private void writeAll(Path envPath, Map<String, String> values) throws IOException {
        StringBuilder sb = new StringBuilder("# managed by dashboard\n");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=')
                .append(entry.getValue() == null ? "" : entry.getValue().replace("\n", ""))
                .append('\n');
        }
        Files.writeString(envPath, sb.toString(), StandardCharsets.UTF_8);
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
