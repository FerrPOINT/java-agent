package com.azhukov.agent.core.security;

import com.azhukov.agent.core.util.TextTruncator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes and stores SHA-256 fingerprints of MCP tool definitions (description +
 * canonical JSON of inputSchema). On subsequent registrations, compares hashes.
 * If a tool's fingerprint has changed, logs a WARN with 'RUG PULL DETECTED' and
 * the diff, indicating the tool definition was modified after initial registration.
 *
 * <p>This detects "MCP rug pull" attacks where a tool's behavior changes after
 * the user has approved it.
 */
@Slf4j
@Component
public class ToolFingerprintStore {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, String> fingerprints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> descriptions = new ConcurrentHashMap<>();

    public ToolFingerprintStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Records or updates the fingerprint for a tool. If the tool was previously
     * registered with a different fingerprint, logs a rug-pull warning.
     *
     * @param toolName    the full tool name (e.g. "server__tool")
     * @param description the tool description
     * @param inputSchema the tool input schema as a Map
     * @return {@code true} if this is a new registration or the fingerprint is unchanged,
     *         {@code false} if a rug pull was detected (fingerprint changed)
     */
    public boolean recordFingerprint(String toolName, String description, Map<String, Object> inputSchema) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        String desc = description == null ? "" : description;
        Map<String, Object> schema = inputSchema != null ? inputSchema : Map.of();

        String newFingerprint = computeFingerprint(desc, schema);
        String oldFingerprint = fingerprints.put(toolName, newFingerprint);
        String oldDescription = descriptions.put(toolName, desc);

        if (oldFingerprint != null && !oldFingerprint.equals(newFingerprint)) {
            log.warn("RUG PULL DETECTED: Tool '{}' definition has changed! " +
                "Old fingerprint: {}, New fingerprint: {}. " +
                "Description diff: '{}' -> '{}'",
                toolName,
                TextTruncator.truncate(oldFingerprint, 16),
                TextTruncator.truncate(newFingerprint, 16),
                TextTruncator.truncate(oldDescription == null ? "" : oldDescription, 100),
                TextTruncator.truncate(desc, 100));
            return false;
        }
        return true;
    }

    /**
     * Gets the current fingerprint for a tool, or null if not registered.
     */
    public String getFingerprint(String toolName) {
        return fingerprints.get(toolName);
    }

    /**
     * Checks whether a tool is already registered.
     */
    public boolean isRegistered(String toolName) {
        return fingerprints.containsKey(toolName);
    }

    /**
     * Removes the fingerprint for a tool (e.g. when the tool is deregistered).
     */
    public void remove(String toolName) {
        fingerprints.remove(toolName);
        descriptions.remove(toolName);
    }

    /**
     * Clears all stored fingerprints.
     */
    public void clear() {
        fingerprints.clear();
        descriptions.clear();
    }

    /**
     * Computes SHA-256 of: description + canonical JSON of inputSchema.
     */
    String computeFingerprint(String description, Map<String, Object> inputSchema) {
        try {
            String canonicalSchema = canonicalizeSchema(inputSchema);
            String combined = description + "\n" + canonicalSchema;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Converts the input schema to canonical JSON (sorted keys for consistency).
     */
    @SuppressWarnings("unchecked")
    String canonicalizeSchema(Map<String, Object> schema) {
        try {
            // Use ObjectMapper with ORDERED map for canonical output
            return objectMapper.writeValueAsString(sortKeys(schema));
        } catch (Exception e) {
            // Fallback: use toString
            return String.valueOf(schema);
        }
    }

    @SuppressWarnings("unchecked")
    private Object sortKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new java.util.TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
            }
            return sorted;
        } else if (value instanceof List<?> list) {
            List<Object> sortedList = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                sortedList.add(sortKeys(item));
            }
            return sortedList;
        }
        return value;
    }

}