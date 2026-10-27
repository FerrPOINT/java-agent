package com.azhukov.agent.core.security;

import com.azhukov.agent.core.model.IdentityDeletionProtocol.Failure;
import com.azhukov.agent.core.model.IdentityDeletionProtocol.Identity;
import com.azhukov.agent.core.model.IdentityDeletionProtocol.Request;
import com.azhukov.agent.core.model.IdentityDeletionProtocol.Window;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP contract tests; no central Auth, DB or runtime acceptance claim. */
class IdentityCoordinatorVerifierHttpTest {
    private static final String ISSUER = "https://auth.example.test";
    private static final String COORDINATOR = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";
    private static final String PAT = "sdlc_pat_synthetic_fixture_only";
    private static final String SCOPE = "java-agent:identity-lifecycle";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Request request(String issuer) {
        return new Request(1, UUID.randomUUID(), new Identity(issuer, OTHER), UUID.randomUUID());
    }

    private static long originalDeadline() {
        return System.nanoTime() + Duration.ofSeconds(30).toNanos();
    }

    private static Map<String, Object> body() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sub", COORDINATOR);
        value.put("email", "coordinator@synthetic.example.test");
        value.put("subject_role", "service_account");
        value.put("scopes", List.of(SCOPE));
        value.put("observed_at", Instant.now().minusSeconds(1).toString());
        value.put("expires_at", Instant.now().plusSeconds(60).toString());
        return value;
    }

    private static void failure(int status, String code, Runnable action) {
        Failure result = assertThrows(Failure.class, action::run);
        assertEquals(status, result.status());
        assertEquals(code, result.code());
    }

    private static final class Stub implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger redirectCalls = new AtomicInteger();
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> query = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> accept = new AtomicReference<>();
        final AtomicReference<String> response = new AtomicReference<>();
        final AtomicInteger status = new AtomicInteger(200);

        Stub() throws Exception {
            response.set(JSON.writeValueAsString(body()));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/auth/tokens/introspect", exchange -> {
                calls.incrementAndGet();
                method.set(exchange.getRequestMethod());
                query.set(exchange.getRequestURI().getRawQuery());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                if (status.get() == 302) exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.createContext("/redirect-target", exchange -> {
                redirectCalls.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
        }

        MockEnvironment environment() {
            return new MockEnvironment()
                .withProperty("agent.central-auth.issuer", ISSUER)
                .withProperty("agent.central-auth.session-uri", "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/auth/session")
                .withProperty("agent.identity-deletion.coordinator-subjects", "  " + COORDINATOR + " , " + COORDINATOR + "  ");
        }

        IdentityCoordinatorVerifier verifier() {
            IdentityCoordinatorVerifier result = new IdentityCoordinatorVerifier(environment(), JSON);
            result.init();
            return result;
        }

        void response(Map<String, Object> body) throws Exception {
            response.set(JSON.writeValueAsString(body));
        }

        @Override public void close() { server.stop(0); }
    }

    @Test
    void registeredServicePatUsesExactFreshIntrospectionAndPreservesOriginalDeadline() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
            Window first = verifier.verify("Bearer " + PAT, request(ISSUER), deadline);
            assertEquals(deadline, first.deadlineNanos());
            first.requireCurrent();
            Window second = verifier.verify("Bearer pdlc_pat_legacy_synthetic", request(ISSUER), originalDeadline());
            second.requireCurrent();
            assertEquals(2, stub.calls.get(), "each verification must confirm the current PAT");
            assertEquals("GET", stub.method.get());
            assertEquals("include_lifetime=true", stub.query.get());
            assertEquals("Bearer pdlc_pat_legacy_synthetic", stub.authorization.get());
            assertEquals("application/json", stub.accept.get());
        }
    }

    @Test
    void confirmedLongLifetimeIsCappedAtNineSecondsAndOriginalExpiryIsRetained() throws Exception {
        try (Stub stub = new Stub()) {
            Instant expires = Instant.now().plusSeconds(60);
            Map<String, Object> response = body(); response.put("expires_at", expires.toString()); stub.response(response);
            long before = System.nanoTime();
            Window window = stub.verifier().verify("Bearer " + PAT, request(ISSUER), originalDeadline());
            long after = System.nanoTime();
            assertEquals(expires, window.expiresAt());
            assertTrue(window.deadlineNanos() >= before + Duration.ofSeconds(9).toNanos());
            assertTrue(window.deadlineNanos() <= after + Duration.ofSeconds(9).toNanos());
            window.requireCurrent();
        }
    }

    @Test
    void shortConfirmedLifetimeAttenuatesInsteadOfReceivingFreshNineSeconds() throws Exception {
        try (Stub stub = new Stub()) {
            Instant observed = Instant.now(); Instant expires = observed.plusSeconds(4);
            Map<String, Object> response = body(); response.put("observed_at", observed.toString());
            response.put("expires_at", expires.toString()); stub.response(response);
            long before = System.nanoTime();
            Window window = stub.verifier().verify("Bearer " + PAT, request(ISSUER), originalDeadline());
            long after = System.nanoTime();
            assertEquals(expires, window.expiresAt());
            assertTrue(window.deadlineNanos() >= before + Duration.ofSeconds(4).toNanos());
            assertTrue(window.deadlineNanos() <= after + Duration.ofSeconds(4).toNanos());
        }
    }

    @Test
    void missingWrongSchemeJwtAndOversizeTokenAreRejectedBeforeAnyUpstreamRequest() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (String authorization : new String[] {null, "", "Basic fixture", "bearer " + PAT}) {
                failure(401, "identity_coordinator_required", () -> verifier.verify(authorization, request(ISSUER), originalDeadline()));
            }
            for (String authorization : List.of("Bearer eyJhbGci.fixture", "Bearer sdlc_pat_" + "x".repeat(16384))) {
                failure(403, "identity_coordinator_capability_required", () -> verifier.verify(authorization, request(ISSUER), originalDeadline()));
            }
            assertEquals(0, stub.calls.get());
        }
    }

    @Test
    void alreadyExpiredOriginalDeadlineCannotBeRenewedByIntrospection() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            failure(401, "identity_authority_expired", () -> verifier.verify("Bearer " + PAT, request(ISSUER), System.nanoTime() - 1));
            assertEquals(0, stub.calls.get());
        }
    }

    @Test
    void upstreamUnauthorizedAndForbiddenBothRejectCurrentCredential() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (int status : new int[] {401, 403}) {
                stub.status.set(status);
                failure(401, "identity_coordinator_rejected", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
            assertEquals(2, stub.calls.get());
        }
    }

    @Test
    void degradedAndUnexpectedStatusesAreUnavailableDespiteAValidLookingBody() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (int status : new int[] {503, 500, 404}) {
                stub.status.set(status);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
        }
    }

    @Test
    void redirectsAreNotFollowedAndCannotSupplyCredentialAuthority() throws Exception {
        try (Stub stub = new Stub()) {
            stub.status.set(302);
            failure(503, "identity_owner_confirmation_unavailable", () -> stub.verifier().verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            assertEquals(1, stub.calls.get());
            assertEquals(0, stub.redirectCalls.get());
        }
    }

    @Test
    void humanRoleUnregisteredServiceAndMissingLifecycleScopeAreDenied() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (String key : List.of("subject_role", "sub", "scopes")) {
                Map<String, Object> response = body();
                response.put(key, switch (key) {
                    case "subject_role" -> "admin";
                    case "sub" -> OTHER;
                    default -> List.of("application-access:read");
                });
                stub.response(response);
                failure(403, "identity_coordinator_capability_required", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
        }
    }

    @Test
    void emptyCoordinatorRegistrationAndWrongIdentityIssuerAreDenied() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = new IdentityCoordinatorVerifier(stub.environment()
                .withProperty("agent.identity-deletion.coordinator-subjects", ""), JSON);
            verifier.init();
            failure(403, "identity_coordinator_capability_required", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            IdentityCoordinatorVerifier configured = stub.verifier();
            failure(403, "identity_coordinator_capability_required", () -> configured.verify("Bearer " + PAT, request("https://foreign.example.test"), originalDeadline()));
        }
    }

    @Test
    void malformedSubjectBlankEmailMissingFieldsAndMalformedJsonFailClosed() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (String subject : List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000", "00000000-0000-4000-8000-1")) {
                Map<String, Object> response = body(); response.put("sub", subject); stub.response(response);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
            for (String field : List.of("sub", "email", "subject_role", "observed_at", "expires_at")) {
                Map<String, Object> response = body(); response.remove(field); stub.response(response);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
            Map<String, Object> response = body(); response.put("email", "  "); stub.response(response);
            failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            for (String invalid : List.of("{", "null", "[]")) {
                stub.response.set(invalid);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
        }
    }

    @Test
    void scopesMustBeAnArrayOfStringsEvenIfOneEntryGrantsLifecycle() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (Object scopes : List.of(SCOPE, List.of(SCOPE, 7))) {
                Map<String, Object> response = body(); response.put("scopes", scopes); stub.response(response);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
            Map<String, Object> response = body(); response.remove("scopes"); stub.response(response);
            failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
        }
    }

    @Test
    void invalidOrOverflowingTimestampCannotSupplyAuthority() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            for (String expires : List.of("not-an-instant", "+999999999-12-31T23:59:59Z")) {
                Map<String, Object> response = body(); response.put("expires_at", expires); stub.response(response);
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
        }
    }

    @Test
    void expiredReversedAndFutureObservedLifetimeAreRejectedWithoutRenewal() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            Instant now = Instant.now();
            for (Instant[] times : List.of(new Instant[] {now.minusSeconds(20), now.minusSeconds(10)},
                    new Instant[] {now, now}, new Instant[] {now.plusSeconds(60), now.plusSeconds(120)})) {
                Map<String, Object> response = body(); response.put("observed_at", times[0].toString());
                response.put("expires_at", times[1].toString()); stub.response(response);
                failure(401, "identity_authority_expired", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
            }
        }
    }

    @Test
    void responseBodyLimitAcceptsExactBoundAndRejectsOneExtraByte() throws Exception {
        try (Stub stub = new Stub()) {
            String valid = JSON.writeValueAsString(body());
            String exact = valid + " ".repeat(65536 - valid.getBytes(StandardCharsets.UTF_8).length);
            stub.response.set(exact);
            IdentityCoordinatorVerifier verifier = stub.verifier();
            verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()).requireCurrent();
            stub.response.set(exact + " ");
            failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
        }
    }

    @Test
    void sessionEndpointRejectsUntrustedUriShapesDuringInitialization() {
        for (String session : List.of("file:///tmp/auth", "http://user@localhost/auth/session", "http://localhost/auth/session?x=1",
                "http://localhost/auth/session#fragment", "ftp://localhost/auth/session", "/auth/session")) {
            MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.central-auth.issuer", ISSUER)
                .withProperty("agent.central-auth.session-uri", session);
            IdentityCoordinatorVerifier verifier = new IdentityCoordinatorVerifier(environment, JSON);
            failure(503, "identity_owner_confirmation_unavailable", verifier::init);
        }
    }

    @Test
    void interruptedVerificationFailsClosedAndPreservesInterruptSignal() throws Exception {
        try (Stub stub = new Stub()) {
            IdentityCoordinatorVerifier verifier = stub.verifier();
            try {
                Thread.currentThread().interrupt();
                failure(503, "identity_owner_confirmation_unavailable", () -> verifier.verify("Bearer " + PAT, request(ISSUER), originalDeadline()));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // Do not contaminate the next JUnit test thread.
            }
        }
    }

    @Test
    void missingRequiredConfigurationCannotInitializeVerifier() {
        assertThrows(IllegalStateException.class, () -> new IdentityCoordinatorVerifier(new MockEnvironment(), JSON).init());
        MockEnvironment issuerOnly = new MockEnvironment().withProperty("agent.central-auth.issuer", ISSUER);
        assertThrows(IllegalStateException.class, () -> new IdentityCoordinatorVerifier(issuerOnly, JSON).init());
    }
}
