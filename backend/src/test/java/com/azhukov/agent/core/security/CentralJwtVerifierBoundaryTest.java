package com.azhukov.agent.core.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** No network: only the external HTTP/decoder boundaries are substituted. */
@ExtendWith(MockitoExtension.class)
class CentralJwtVerifierBoundaryTest {
    private static final String SUBJECT = "8c5ddf41-ae6d-4dc9-a7ec-15835dd6303e";
    private static final String ISSUER = "https://auth.fixture.invalid";
    private static final String PAT = "sdlc_pat_synthetic_fixture";
    private static final String PERSONAL = "https://auth.fixture.invalid/introspect";

    @ParameterizedTest
    @ValueSource(strings = {"/relative", "file:///tmp/fixture", "https://user@auth.fixture.invalid/me",
            "https://auth.fixture.invalid/me?token=fixture", "https://auth.fixture.invalid/me#fragment"})
    void invalidSessionOrPersonalEndpointsAreRejectedBeforeServingIdentity(String endpoint) {
        for (String property : List.of("session-uri", "personal-token-uri")) {
            var environment = environment().withProperty("agent.central-auth." + property, endpoint);
            assertThatThrownBy(() -> new CentralJwtVerifier(environment).init())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "jwks-uri", "audience"})
    void blankRequiredDeploymentAuthorityIsRejected(String property) {
        var environment = environment().withProperty("agent.central-auth." + property, " ");
        assertThatThrownBy(() -> new CentralJwtVerifier(environment).init())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noPersonalIntrospectionEndpointCannotFallBackToJwtOrSessionConfirmation() {
        var verifier = new CentralJwtVerifier(environment().withProperty("agent.central-auth.personal-token-uri", ""));
        verifier.init();
        var client = mock(HttpClient.class);
        ReflectionTestUtils.setField(verifier, "sessionClient", client);

        assertThatThrownBy(() -> verifier.verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
        verifyNoInteractions(client);
    }

    @Test
    void emptyOrOversizedBearerIsRejectedBeforeAnyExternalCall() {
        var verifier = new CentralJwtVerifier(environment());
        verifier.init();
        var client = mock(HttpClient.class);
        ReflectionTestUtils.setField(verifier, "sessionClient", client);
        for (String token : new String[]{null, " ", "x".repeat(16385)}) {
            assertThatThrownBy(() -> verifier.verifyAccess(token, "GET")).isInstanceOf(BadJwtException.class);
        }
        verifyNoInteractions(client);
    }

    @Test
    void decoderInfrastructureFailureRemainsUnavailableRatherThanInvalidCredentials() {
        var verifier = new CentralJwtVerifier(environment());
        verifier.init();
        var decoder = mock(JwtDecoder.class);
        var client = mock(HttpClient.class);
        when(decoder.decode("synthetic-jwt")).thenThrow(new JwtException("synthetic key service failure"));
        ReflectionTestUtils.setField(verifier, "decoder", decoder);
        ReflectionTestUtils.setField(verifier, "sessionClient", client);

        assertThatThrownBy(() -> verifier.verifyAccess("synthetic-jwt", "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-0000-0000-000000000000",
            "8C5DDF41-AE6D-4DC9-A7EC-15835DD6303E", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"})
    void aPersonalTokenCannotNameAnAliasOrNonCanonicalOwner(String subject) throws Exception {
        var identity = identity();
        identity.put("sub", subject);
        var fixture = fixture(json(identity));

        assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET")).isInstanceOf(BadJwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed"})
    void absentOrMalformedOriginalPatLifetimeNeverCreatesAProofWindow(String failure) throws Exception {
        var identity = identity();
        if (failure.equals("missing")) identity.remove("expires_at");
        else identity.put("expires_at", "not-a-date");
        var fixture = fixture(json(identity));

        assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
    }

    @Test
    void writeScopeIsConfirmedAtThePersonalEndpointWithOriginalLifetimeRequested() throws Exception {
        var identity = identity();
        identity.put("scopes", List.of("java-agent:write"));
        var fixture = fixture(json(identity));

        var proof = fixture.verifier().verifyIdentity(PAT, "POST");

        assertThat(proof.issuer()).isEqualTo(ISSUER);
        assertThat(proof.access().subject()).isEqualTo(SUBJECT);
        assertThat(proof.window().current()).isTrue();
        assertThat(proof.window().deadlineNanos()).isEqualTo(proof.access().deadlineNanos());
        verify(fixture.client()).sendAsync(argThat((HttpRequest request) -> request.uri().equals(URI.create(PERSONAL + "?include_lifetime=true"))
                && request.method().equals("GET")
                && request.headers().firstValue("Authorization").orElse("").equals("Bearer " + PAT)
                && request.timeout().orElseThrow().equals(java.time.Duration.ofSeconds(5))),
                any(HttpResponse.BodyHandler.class));
    }

    @Test
    void missingRequiredApplicationDecisionDeniesEvenAValidPat() throws Exception {
        var fixture = fixture(json(identity()), true);
        assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAccessDeniedException.class);
    }

    @Test
    void malformedApplicationDecisionIsUnavailableAndDoesNotReuseLegacyRole() throws Exception {
        var identity = identity();
        identity.put("role", "admin");
        identity.put("application_access", Map.of("schema_version", 1));
        var fixture = fixture(json(identity));
        var verifier = fixture.verifier();
        assertThatThrownBy(() -> verifier.verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
    }

    @Test
    void jsonNullReadbackCannotBecomeAUser() {
        var fixture = fixture("null");
        assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
    }

    @Test
    void timedOutConfirmationIsCancelledAndNeverProducesAnIdentity() throws Exception {
        var pending = mock(CompletableFuture.class);
        when(pending.get(5, TimeUnit.SECONDS)).thenThrow(new TimeoutException("synthetic timeout"));
        var fixture = fixture(pending);

        assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET"))
                .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
        verify(pending).cancel(true);
    }

    @Test
    void interruptedConfirmationCancelsWorkAndPreservesTheInterruptFlag() throws Exception {
        var pending = mock(CompletableFuture.class);
        when(pending.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("synthetic interrupt"));
        var fixture = fixture(pending);
        try {
            assertThatThrownBy(() -> fixture.verifier().verifyAccess(PAT, "GET"))
                    .isInstanceOf(CentralJwtVerifier.CentralAuthUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(pending).cancel(true);
        } finally {
            Thread.interrupted();
        }
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("agent.central-auth.issuer", ISSUER)
                .withProperty("agent.central-auth.jwks-uri", "https://auth.fixture.invalid/jwks")
                .withProperty("agent.central-auth.audience", "sdlc")
                .withProperty("agent.central-auth.session-uri", "https://auth.fixture.invalid/me")
                .withProperty("agent.central-auth.personal-token-uri", PERSONAL);
    }

    private static Map<String, Object> identity() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("sub", SUBJECT);
        result.put("status", "active");
        result.put("scopes", List.of("java-agent:read"));
        result.put("expires_at", Instant.now().plusSeconds(60).toString());
        return result;
    }

    private static String json(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    private static Fixture fixture(String body) {
        return fixture(body, false);
    }

    private static Fixture fixture(String body, boolean required) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return fixture(CompletableFuture.completedFuture(response), required);
    }

    private static Fixture fixture(CompletableFuture<HttpResponse<byte[]>> pending) {
        return fixture(pending, false);
    }

    private static Fixture fixture(CompletableFuture<HttpResponse<byte[]>> pending, boolean required) {
        var verifier = new CentralJwtVerifier(environment().withProperty(
                "agent.central-auth.application-access-required", Boolean.toString(required)));
        verifier.init();
        var client = mock(HttpClient.class);
        doReturn(pending).when(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        ReflectionTestUtils.setField(verifier, "sessionClient", client);
        return new Fixture(verifier, client);
    }

    private record Fixture(CentralJwtVerifier verifier, HttpClient client) {}
}
