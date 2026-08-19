package com.azhukov.agent.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFingerprintStoreTest {

    private ToolFingerprintStore store;

    @BeforeEach
    void setUp() {
        store = new ToolFingerprintStore(new ObjectMapper());
    }

    @Test
    void firstRegistrationReturnsTrue() {
        boolean result = store.recordFingerprint("tool1", "A search tool", Map.of("type", "object"));
        assertThat(result).isTrue();
    }

    @Test
    void sameRegistrationReturnsTrue() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        store.recordFingerprint("tool1", "Search tool", schema);
        boolean result = store.recordFingerprint("tool1", "Search tool", schema);
        assertThat(result).isTrue();
    }

    @Test
    void changedDescriptionReturnsFalse() {
        store.recordFingerprint("tool1", "Original description", Map.of());
        boolean result = store.recordFingerprint("tool1", "Modified description", Map.of());
        assertThat(result).isFalse();
    }

    @Test
    void changedSchemaReturnsFalse() {
        Map<String, Object> schema1 = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        Map<String, Object> schema2 = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "integer")));
        store.recordFingerprint("tool1", "Tool", schema1);
        boolean result = store.recordFingerprint("tool1", "Tool", schema2);
        assertThat(result).isFalse();
    }

    @Test
    void changedRequiredReturnsFalse() {
        Map<String, Object> schema1 = Map.of("type", "object", "required", java.util.List.of("q"));
        Map<String, Object> schema2 = Map.of("type", "object", "required", java.util.List.of("q", "r"));
        store.recordFingerprint("tool1", "Tool", schema1);
        boolean result = store.recordFingerprint("tool1", "Tool", schema2);
        assertThat(result).isFalse();
    }

    @Test
    void differentToolsDoNotConflict() {
        store.recordFingerprint("tool1", "Tool A", Map.of());
        boolean result = store.recordFingerprint("tool2", "Tool A", Map.of());
        assertThat(result).isTrue();
    }

    @Test
    void isRegisteredReturnsTrueAfterRegistration() {
        store.recordFingerprint("tool1", "Tool", Map.of());
        assertThat(store.isRegistered("tool1")).isTrue();
        assertThat(store.isRegistered("tool2")).isFalse();
    }

    @Test
    void getFingerprintReturnsHash() {
        store.recordFingerprint("tool1", "Tool", Map.of("type", "object"));
        String fp = store.getFingerprint("tool1");
        assertThat(fp).isNotNull();
        assertThat(fp).hasSize(64); // SHA-256 hex = 64 chars
    }

    @Test
    void removeClearsFingerprint() {
        store.recordFingerprint("tool1", "Tool", Map.of());
        store.remove("tool1");
        assertThat(store.isRegistered("tool1")).isFalse();
        assertThat(store.getFingerprint("tool1")).isNull();
    }

    @Test
    void clearRemovesAllFingerprints() {
        store.recordFingerprint("tool1", "A", Map.of());
        store.recordFingerprint("tool2", "B", Map.of());
        store.clear();
        assertThat(store.isRegistered("tool1")).isFalse();
        assertThat(store.isRegistered("tool2")).isFalse();
    }

    @Test
    void nullDescriptionHandled() {
        boolean result = store.recordFingerprint("tool1", null, Map.of());
        assertThat(result).isTrue();
        // Second registration with null should be same
        result = store.recordFingerprint("tool1", null, Map.of());
        assertThat(result).isTrue();
    }

    @Test
    void nullSchemaHandled() {
        boolean result = store.recordFingerprint("tool1", "Tool", null);
        assertThat(result).isTrue();
    }

    @Test
    void sameSchemaDifferentKeyOrderSameFingerprint() {
        // JSON canonicalization should produce same fingerprint regardless of key order
        Map<String, Object> schema1 = new LinkedHashMap<>();
        schema1.put("a", "1");
        schema1.put("b", "2");
        Map<String, Object> schema2 = new LinkedHashMap<>();
        schema2.put("b", "2");
        schema2.put("a", "1");
        store.recordFingerprint("tool1", "Tool", schema1);
        boolean result = store.recordFingerprint("tool1", "Tool", schema2);
        assertThat(result).isTrue();
    }

    @Test
    void fingerprintIsDeterministic() {
        String desc = "A tool";
        Map<String, Object> schema = Map.of("type", "object");
        String fp1 = store.computeFingerprint(desc, schema);
        String fp2 = store.computeFingerprint(desc, schema);
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void differentDescriptionsProduceDifferentFingerprints() {
        String fp1 = store.computeFingerprint("desc1", Map.of());
        String fp2 = store.computeFingerprint("desc2", Map.of());
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void rugPullDetectedOnReRegistrationWithChange() {
        Map<String, Object> schema1 = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        Map<String, Object> schema2 = Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string", "description", "new")));

        store.recordFingerprint("server__tool", "Original tool", schema1);
        boolean unchanged = store.recordFingerprint("server__tool", "Modified tool", schema2);
        assertThat(unchanged).isFalse();
    }

    @Test
    void reRegistrationWithSameDataReturnsTrue() {
        Map<String, Object> schema = Map.of("type", "object");
        store.recordFingerprint("tool", "Desc", schema);
        store.recordFingerprint("tool", "Desc", schema);
        boolean result = store.recordFingerprint("tool", "Desc", schema);
        assertThat(result).isTrue();
    }
}