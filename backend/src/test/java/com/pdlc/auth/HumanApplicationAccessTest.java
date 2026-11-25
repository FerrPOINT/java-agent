package com.pdlc.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HumanApplicationAccessTest {
    private static final String ISSUER = "https://auth.fixture.invalid";
    private static final String SUBJECT = "current-human";

    @Test
    void credentialWithoutApplicationProofKeepsItsOriginalFiveMinuteWindow() {
        Instant observed = Instant.now();
        long started = System.nanoTime();
        Instant expiry = observed.plusSeconds(300);
        var credential = new HumanApplicationAccess.Credential("user", observed, expiry);
        var result = HumanApplicationAccess.confirm(Map.of(), ISSUER, SUBJECT,
                started, observed, credential, false);
        assertEquals(expiry, result.expiresAt());
        assertEquals(started + Duration.ofSeconds(300).toNanos(), result.deadlineNanos());
        assertTrue(result.isCurrent());
        assertTrue(result.permits(Set.of("java-agent"), false));
        assertFalse(result.permits(Set.of("java-agent"), true));
    }

    @Test
    void cachedSourceObservationCannotRenewAbsoluteCredentialExpiry() {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        var credential = new HumanApplicationAccess.Credential(
                "admin", startedAt.minusSeconds(200), startedAt.plusSeconds(100));
        var result = HumanApplicationAccess.confirm(Map.of(), ISSUER, SUBJECT,
                started, startedAt, credential, false);
        assertEquals(started + Duration.ofSeconds(100).toNanos(), result.deadlineNanos());
    }

    @Test
    void actualApplicationProofRetainsItsOwnEarlierDeadline() {
        Instant observed = Instant.now();
        long started = System.nanoTime();
        var credential = new HumanApplicationAccess.Credential("admin", observed, observed.plusSeconds(300));
        var grant = Map.<String, Object>of(
                "schema_version", 1, "issuer", ISSUER, "subject", SUBJECT, "role", "user",
                "applications", List.of("java-agent"), "revision", 1, "enforced", true,
                "observed_at", observed.toString(), "expires_at", observed.plusSeconds(30).toString());
        var result = HumanApplicationAccess.confirm(Map.of("application_access", grant),
                ISSUER, SUBJECT, started, observed, credential, true);
        assertEquals(observed.plusSeconds(30), result.expiresAt());
        assertEquals(started + Duration.ofSeconds(30).toNanos(), result.deadlineNanos());
        assertEquals("user", result.role());
        assertTrue(result.permits(Set.of("java-agent"), false));
        assertFalse(result.permits(Set.of("wiki"), false));
        assertFalse(result.permits(Set.of("java-agent"), true));
    }

    @Test
    void requiredProofAndTechnicalIdentityRemainClosed() {
        Instant now = Instant.now();
        long started = System.nanoTime();
        assertThrows(HumanApplicationAccess.AdmissionDeniedException.class,
                () -> HumanApplicationAccess.confirm(Map.of(), ISSUER, SUBJECT, started, now,
                        new HumanApplicationAccess.Credential("user", now, now.plusSeconds(300)), true));
        assertThrows(HumanApplicationAccess.AdmissionDeniedException.class,
                () -> HumanApplicationAccess.confirm(Map.of(), ISSUER, SUBJECT, started, now,
                        new HumanApplicationAccess.Credential("service_account", now, null), false));
    }

    @Test
    void missingCurrentRoleDoesNotBecomeAnOrdinaryUser() {
        Instant now = Instant.now();
        long started = System.nanoTime();
        assertThrows(IllegalArgumentException.class,
                () -> HumanApplicationAccess.confirm(Map.of("role", "admin"), ISSUER, SUBJECT, started, now,
                        new HumanApplicationAccess.Credential(null, now, now.plusSeconds(300)), false));
    }

    @Test
    void expiredCredentialCannotBeRevivedByAYoungerApplicationProof() {
        Instant now = Instant.now();
        long started = System.nanoTime();
        var grant = Map.<String, Object>of(
                "schema_version", 1, "issuer", ISSUER, "subject", SUBJECT, "role", "admin",
                "applications", List.of("java-agent"), "revision", 2, "enforced", true,
                "observed_at", now.toString(), "expires_at", now.plusSeconds(30).toString());
        assertThrows(HumanApplicationAccess.AdmissionDeniedException.class,
                () -> HumanApplicationAccess.confirm(Map.of("application_access", grant), ISSUER, SUBJECT, started, now,
                        new HumanApplicationAccess.Credential("admin", now.minusSeconds(100), now.minusSeconds(1)), true));
    }
}
