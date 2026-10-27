package com.azhukov.agent.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Base identity-deletion wire receipts; these values carry no transport authority. */
public final class IdentityDeletionProtocol {
    private IdentityDeletionProtocol() {}
    @Schema(name="JavaIdentityDeletionSubject")
    public record Identity(String issuer, String subject) {}
    @Schema(name="JavaIdentityDeletionRequest")
    public record Request(@JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("operation_id") UUID operationId, Identity identity,
            @JsonProperty("inventory_revision") UUID inventoryRevision) {
        public boolean valid() {
            if (schemaVersion != 1 || empty(operationId) || empty(inventoryRevision) || identity == null
                    || identity.issuer() == null || identity.issuer().length() > 2048
                    || identity.subject() == null || identity.subject().length() > 64) return false;
            try {
                UUID subject = UUID.fromString(identity.subject());
                URI issuer = URI.create(identity.issuer());
                return !empty(subject) && subject.toString().equals(identity.subject())
                    && issuer.getHost() != null && issuer.getUserInfo() == null
                    && issuer.getQuery() == null && issuer.getFragment() == null
                    && ("http".equals(issuer.getScheme()) || "https".equals(issuer.getScheme()));
            } catch (IllegalArgumentException invalid) { return false; }
        }
    }
    @Schema(name="JavaIdentityReferenceSummary")
    public record Summary(Request request, String owner, long revision,
            @JsonProperty("reference_count") long referenceCount,
            @JsonProperty("fencing_supported") boolean fencingSupported) {}
    @Schema(name="JavaIdentityFreezeReceipt")
    public record Freeze(Request request, String owner, UUID fence, long revision,
            @JsonProperty("reference_count") long referenceCount) {}
    @Schema(name="JavaIdentityReleaseReceipt")
    public record Release(Request request, String owner, boolean cancelled) {}
    public record Window(long deadlineNanos, Instant expiresAt) {
        public boolean current() { return deadlineNanos - System.nanoTime() > 0 && Instant.now().isBefore(expiresAt); }
        public void requireCurrent() { if (!current()) throw new Failure(401, "identity_authority_expired"); }
        /** DB time is sampled before measuring remaining original monotonic life. */
        public Instant commitExpiresAt(Instant databaseObservedAt) {
            long remaining=deadlineNanos-System.nanoTime();
            if(remaining<=0)throw new Failure(401,"identity_authority_expired");
            Instant cap=databaseObservedAt.plusNanos(remaining);
            requireCurrent();
            return cap.isBefore(expiresAt)?cap:expiresAt;
        }
    }
    public static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code) { super(code); this.status=status; this.code=code; }
        public int status() { return status; }
        public String code() { return code; }
    }
    private static boolean empty(UUID id) { return id == null || id.equals(new UUID(0,0)); }
}
