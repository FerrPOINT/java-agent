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

    /** Uses the original request start and expiry; a receiving cache cannot add another minute. */
    public static HumanApplicationAccess confirm(Map<String, ?> identity, String issuer, String subject,
            long startedNanos, Instant wallStarted, Instant tokenExpiresAt, boolean requireProof) {
        if ("service_account".equals(identity.get("role"))) {
            throw new AdmissionDeniedException();
        }
        Instant now = Instant.now();
        String role = "admin".equals(identity.get("role")) ? "admin" : "user";
        Set<String> applications = Set.of();
        long revision = 0;
        boolean enforced = false;
        Instant expires = wallStarted.plus(WINDOW);
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
            expires = timestamp(proof.get("expires_at"));
            Duration lifetime = Duration.between(observed, expires);
            HashSet<String> unique = new HashSet<>();
            if (keys.size() > 128) throw new IllegalArgumentException("Invalid application catalog");
            for (Object key : keys) {
                if (!(key instanceof String text) || !text.matches("[a-z0-9-]{1,64}") || !unique.add(text)) {
                    throw new IllegalArgumentException("Invalid application catalog");
                }
            }
            applications = unique;
            if (revision <= 0 || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(WINDOW) > 0
                    || observed.isBefore(wallStarted.minusSeconds(5)) || observed.isAfter(now.plusSeconds(5))) {
                throw new IllegalArgumentException("Invalid human admission decision");
            }
        }
        if (requireProof && !enforced) throw new AdmissionDeniedException();
        if (tokenExpiresAt != null && tokenExpiresAt.isBefore(expires)) expires = tokenExpiresAt;
        Duration remaining = Duration.between(now, expires);
        if (remaining.isNegative() || remaining.isZero() || System.nanoTime() - startedNanos < 0) {
            throw new AdmissionDeniedException();
        }
        long lifetimeNanos = remaining.compareTo(WINDOW) < 0 ? remaining.toNanos() : WINDOW.toNanos();
        HumanApplicationAccess result = new HumanApplicationAccess(subject, role, applications, revision,
                enforced, expires, startedNanos + lifetimeNanos);
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
