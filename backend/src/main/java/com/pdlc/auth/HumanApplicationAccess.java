package com.pdlc.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical dependency-free proof boundary. Call only after verifying the trusted Auth identity. */
public record HumanApplicationAccess(String subject, String role, Set<String> applications,
        long revision, boolean enforced, Instant expiresAt, long deadlineNanos) {
    private static final Set<String> FIELDS = Set.of("schema_version", "issuer", "subject", "role",
            "applications", "revision", "enforced", "observed_at", "expires_at");
    private static final Duration WINDOW = Duration.ofSeconds(60);

    public HumanApplicationAccess {
        applications = Set.copyOf(applications);
    }

    public boolean isCurrent() {
        return System.nanoTime() - deadlineNanos < 0 && Instant.now().isBefore(expiresAt);
    }

    public boolean permits(Set<String> required, boolean administration) {
        return isCurrent() && (!administration || "admin".equals(role))
                && ("admin".equals(role) || !enforced || applications.containsAll(required));
    }

    /** Current role and source clocks, resolved once from the credential's Auth response. */
    public record Credential(String role, Instant observedAt, Instant expiresAt) {}

    /** Uses original credential and grant clocks; a receiving cache cannot renew either. */
    public static HumanApplicationAccess confirm(Map<String, ?> identity, String issuer, String subject,
            long startedNanos, Instant wallStarted, Credential credential, boolean requireProof) {
        if ("service_account".equals(credential.role())) {
            throw new AdmissionDeniedException();
        }
        Instant now = Instant.now();
        String role = credential.role();
        if (!("user".equals(role) || "admin".equals(role))) {
            throw new IllegalArgumentException("Invalid current human role");
        }
        Set<String> applications = Set.of();
        long revision = 0;
        boolean enforced = false;
        Instant expires = credential.expiresAt();
        long credentialRemaining = Math.min(
                Duration.between(wallStarted, expires).toNanos(),
                Duration.between(credential.observedAt(), expires).toNanos());
        if (credentialRemaining <= 0) throw new AdmissionDeniedException();
        long deadline = startedNanos + credentialRemaining;
        Object value = identity.get("application_access");
        if (value != null) {
            if (!(value instanceof Map<?, ?> proof) || !proof.keySet().equals(FIELDS)
                    || number(proof.get("schema_version")) != 1
                    || !issuer.equals(proof.get("issuer")) || !subject.equals(proof.get("subject"))
                    || !(proof.get("role") instanceof String currentRole)
                    || !("user".equals(currentRole) || "admin".equals(currentRole))
                    || !(proof.get("applications") instanceof List<?> keys)
                    || !(proof.get("enforced") instanceof Boolean enabled)) {
                throw new IllegalArgumentException("Invalid human admission shape");
            }
            role = currentRole;
            revision = number(proof.get("revision"));
            enforced = enabled;
            Instant observed = timestamp(proof.get("observed_at"));
            Instant grantExpires = timestamp(proof.get("expires_at"));
            Duration lifetime = Duration.between(observed, grantExpires);
            HashSet<String> unique = new HashSet<>();
            if (keys.size() > 128) throw new IllegalArgumentException("Invalid application catalog");
            for (Object key : keys) {
                if (!(key instanceof String text) || !text.matches("[a-z0-9-]{1,64}") || !unique.add(text)) {
                    throw new IllegalArgumentException("Invalid application catalog");
                }
            }
            applications = unique;
            expires = credential.expiresAt().isBefore(grantExpires) ? credential.expiresAt() : grantExpires;
            long grantRemaining = Math.min(lifetime.toNanos(),
                    Duration.between(wallStarted, grantExpires).toNanos());
            deadline = Math.min(deadline, startedNanos + grantRemaining);
            if (revision <= 0 || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(WINDOW) > 0
                    || observed.isBefore(wallStarted.minusSeconds(5)) || observed.isAfter(now.plusSeconds(5))) {
                throw new IllegalArgumentException("Invalid human admission decision");
            }
        }
        if (requireProof && !enforced) throw new AdmissionDeniedException();
        if (System.nanoTime() - startedNanos < 0) throw new AdmissionDeniedException();
        HumanApplicationAccess result = new HumanApplicationAccess(subject, role, applications, revision,
                enforced, expires, deadline);
        if (!result.isCurrent()) throw new AdmissionDeniedException();
        return result;
    }

    private static long number(Object value) {
        if (value instanceof Integer integer) return integer.longValue();
        if (value instanceof Long integer) return integer;
        throw new IllegalArgumentException("Invalid admission integer");
    }

    private static Instant timestamp(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid admission timestamp");
        try { return OffsetDateTime.parse(text).toInstant(); }
        catch (java.time.DateTimeException malformed) {
            throw new IllegalArgumentException("Invalid admission timestamp");
        }
    }

    public static final class AdmissionDeniedException extends SecurityException {
        private static final long serialVersionUID = 1L;
        public AdmissionDeniedException() { super("Current human admission required"); }
    }
}
