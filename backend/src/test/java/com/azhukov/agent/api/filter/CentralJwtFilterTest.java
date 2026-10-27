package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.CentralJwtVerifier;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.UserAccessService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real ES256/JWKS decoding and the injected filter path protect against a
 * disabled-auth fallback without testing or reproducing decoder internals. */
class CentralJwtFilterTest {
    private static final String ISSUER = "https://qa.sdlc2.invalid";
    private static final String SUBJECT = UUID.randomUUID().toString();
    private static ECKey key;
    private static HttpServer server;
    private static String jwks;
    private static String sessionUrl;
    private static final AtomicInteger sessionStatus = new AtomicInteger(200);
    private static final AtomicInteger unexpectedRequests = new AtomicInteger();
    private static final AtomicInteger sessionConfirmations = new AtomicInteger();
    private static final AtomicReference<String> sessionReply = new AtomicReference<>();
    private static final AtomicReference<Instant> sessionReplyNotBefore = new AtomicReference<>();

    @BeforeAll
    static void startJwks() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("own-fixture").generate();
        byte[] publicKeys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, publicKeys.length);
            try (var output = exchange.getResponseBody()) { output.write(publicKeys); }
        });
        sessionReply.set("{\"id\":\"" + SUBJECT + "\",\"status\":\"active\"}");
        server.createContext("/me", exchange -> {
            sessionConfirmations.incrementAndGet();
            Instant notBefore=sessionReplyNotBefore.get();
            while(notBefore!=null && Instant.now().isBefore(notBefore))java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            byte[] reply = sessionReply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(sessionStatus.get(), reply.length);
            try (var output = exchange.getResponseBody()) { output.write(reply); }
        });
        server.createContext("/unexpected", exchange -> {
            unexpectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        jwks = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        sessionUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/me";
    }

    @AfterAll
    static void stopJwks() { if (server != null) server.stop(0); }

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
        sessionStatus.set(200);
        sessionReply.set("{\"id\":\"" + SUBJECT + "\",\"status\":\"active\"}");
        unexpectedRequests.set(0);
        sessionConfirmations.set(0);
        sessionReplyNotBefore.set(null);
    }

    private String token(Consumer<JWTClaimsSet.Builder> change, ECKey signingKey) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUBJECT)
                .claim("sid", UUID.randomUUID().toString())
                .audience("sdlc").issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(120)));
        change.accept(claims);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(
                signingKey.getCurve().equals(Curve.P_384) ? JWSAlgorithm.ES384 : JWSAlgorithm.ES256)
                .keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new ECDSASigner(signingKey));
        return jwt.serialize();
    }

    private ApiKeyAuthFilter filter() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApiKey("fixture-global-key");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("central", verifier());
        var filter = new ApiKeyAuthFilter(properties, mock(UserAccessService.class));
        filter.configureCentralVerifier(beans.getBeanProvider(CentralJwtVerifier.class));
        return filter;
    }

    private CentralJwtVerifier verifier() {
        var environment = new MockEnvironment()
                .withProperty("agent.central-auth.issuer", ISSUER)
                .withProperty("agent.central-auth.jwks-uri", jwks)
                .withProperty("agent.central-auth.audience", "sdlc")
                .withProperty("agent.central-auth.session-uri", sessionUrl);
        environment.withProperty("agent.central-auth.personal-token-uri", sessionUrl);
        var verifier = new CentralJwtVerifier(environment);
        verifier.init();
        return verifier;
    }

    @Test
    void verifiedSubjectReachesTheControllerWithoutBearerCredentialsAndIsCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer " + token(builder -> {}, key));
        AtomicInteger calls = new AtomicInteger();
        filter().doFilter(request, new MockHttpServletResponse(), (incoming, response) -> {
            calls.incrementAndGet();
            assertThat(UserContext.getUserId()).isEqualTo(SUBJECT);
            assertThat(UserContext.scopeUserId()).isEqualTo(SUBJECT);
            assertThat(UserContext.isAdmin()).isFalse();
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication.getPrincipal()).isEqualTo(SUBJECT);
            assertThat(authentication.getCredentials()).isNull();
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(UserContext.getUserId()).isNull();
    }

    private String admission(String role, String applications) {
        Instant now = Instant.now();
        return "{\"id\":\"" + SUBJECT + "\",\"status\":\"active\",\"role\":\"admin\","
                + "\"application_access\":{\"schema_version\":1,\"issuer\":\"" + ISSUER
                + "\",\"subject\":\"" + SUBJECT + "\",\"role\":\"" + role + "\","
                + "\"applications\":" + applications + ",\"revision\":2,\"enforced\":true,"
                + "\"observed_at\":\"" + now + "\",\"expires_at\":\"" + now.plusSeconds(60) + "\"}}";
    }

    @Test
    void sameSessionUsesCurrentRoleAndApplicationWithoutAllowingHumanImpersonation() throws Exception {
        String encoded = token(builder -> builder.claim("role", "admin"), key);
        var filter = filter();
        for (String role : new String[]{"user", "admin", "user"}) {
            sessionReply.set(admission(role, "[\"java-agent\"]"));
            var request = new MockHttpServletRequest("GET", "/api/v2/sessions");
            request.addHeader("Authorization", "Bearer " + encoded);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) -> {
                assertThat(UserContext.isAdmin()).isEqualTo("admin".equals(role));
                assertThat(UserContext.effectiveUserId("foreign-author")).isEqualTo(SUBJECT);
                assertThat(UserContext.centralAccess()).isNotNull();
            });
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(UserContext.centralAccess()).isNull();
        }
        sessionReply.set(admission("user", "[]"));
        var request = new MockHttpServletRequest("GET", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer " + encoded);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> fail("Revoked application admitted"));
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void personalTokenNeedsBothCurrentApplicationAndMethodScope() throws Exception {
        for (String prefix : new String[]{"sdlc_pat_", "pdlc_pat_"}) {
            for (String method : new String[]{"GET", "POST"}) {
                String proof = admission("admin", "[\"java-agent\"]");
                sessionReply.set(proof.replace("\"id\":", "\"sub\":")
                        .replace("{\"sub\":", "{\"scopes\":[\"java-agent:read\"],\"expires_at\":\""
                                + Instant.now().plusSeconds(120) + "\",\"sub\":"));
                var request = new MockHttpServletRequest(method, "/api/v2/sessions");
                request.addHeader("Authorization", "Bearer " + prefix + "fixture-only");
                var response = new MockHttpServletResponse();
                AtomicInteger calls = new AtomicInteger();
                filter().doFilter(request, response, (incoming, outgoing) -> calls.incrementAndGet());
                assertThat(response.getStatus()).isEqualTo(method.equals("GET") ? 200 : 403);
                assertThat(calls.get()).isEqualTo(method.equals("GET") ? 1 : 0);
            }
        }
    }

    @Test
    void originalSignedExpiryDuringLiveConfirmationNeverReachesController() throws Exception {
        Instant expires=Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        sessionReplyNotBefore.set(expires.plusMillis(30));
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/v2/sessions");
        request.addHeader("Authorization","Bearer "+token(builder->builder.expirationTime(Date.from(expires)),key));
        MockHttpServletResponse response=new MockHttpServletResponse();
        filter().doFilter(request,response,(incoming,outgoing)->fail("Expired live confirmation admitted a user"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void provenanceUsesTheSameAllowedLiveDecisionWithoutConfirmingAgainOrRegisteringDeniedUsers() throws Exception {
        var registration=mock(com.azhukov.agent.core.ports.IdentityReferenceRegistration.class);
        var beans=new StaticListableBeanFactory();beans.addBean("references",registration);
        var filter=filter();filter.configureIdentityReferences(beans.getBeanProvider(com.azhukov.agent.core.ports.IdentityReferenceRegistration.class));
        AtomicReference<com.azhukov.agent.core.model.IdentityDeletionProtocol.Window> registered=new AtomicReference<>();
        org.mockito.Mockito.doAnswer(call->{
            assertThat((String)call.getArgument(0)).isEqualTo(ISSUER);assertThat((String)call.getArgument(1)).isEqualTo(SUBJECT);
            registered.set(call.getArgument(2));assertThat(registered.get().current()).isTrue();return null;
        }).when(registration).central(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        for(boolean allowed:new boolean[]{false,true}) {
            sessionReply.set(admission("user",allowed?"[\"java-agent\"]":"[]"));
            var request=new MockHttpServletRequest("GET","/api/v2/sessions");request.addHeader("Authorization","Bearer "+token(builder->builder.claim("role","admin"),key));
            var response=new MockHttpServletResponse();
            filter.doFilter(request,response,(incoming,outgoing)->{
                assertThat(allowed).isTrue();assertThat(UserContext.isAdmin()).isFalse();
                assertThat(registered.get().deadlineNanos()).isEqualTo(UserContext.centralAccess().deadlineNanos());
            });
            assertThat(response.getStatus()).isEqualTo(allowed?200:403);
        }
        assertThat(sessionConfirmations.get()).isEqualTo(2);
        org.mockito.Mockito.verify(registration,org.mockito.Mockito.times(1)).central(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        assertThat(UserContext.centralAccess()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "audience", "missing_audience", "subject", "zero_subject",
            "expired", "missing_exp", "missing_iat", "future_iat", "missing_sid", "invalid_sid",
            "signature", "algorithm", "malformed"})
    void invalidCentralIdentityDoesNotFallBackToGlobalApiKey(String failure) throws Exception {
        ECKey signing = failure.equals("signature") ? new ECKeyGenerator(Curve.P_256).generate()
                : failure.equals("algorithm") ? new ECKeyGenerator(Curve.P_384).generate() : key;
        String encoded = failure.equals("malformed") ? "invalid-token" : token(builder -> {
            switch (failure) {
                case "issuer" -> builder.issuer("https://qa.sdlc1.invalid");
                case "audience" -> builder.audience("foreign");
                case "missing_audience" -> builder.claim("aud", null);
                case "subject" -> builder.subject("owner-alias");
                case "zero_subject" -> builder.subject(new UUID(0, 0).toString());
                case "expired" -> builder.expirationTime(Date.from(Instant.now().minusSeconds(60)));
                case "missing_exp" -> builder.expirationTime(null);
                case "missing_iat" -> builder.issueTime(null);
                case "future_iat" -> builder.issueTime(Date.from(Instant.now().plusSeconds(60)));
                case "missing_sid" -> builder.claim("sid", null);
                case "invalid_sid" -> builder.claim("sid", "foreign-session-alias");
                default -> { }
            }
        }, signing);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer " + encoded);
        request.addHeader("X-API-Key", "fixture-global-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        filter().doFilter(request, response, (incoming, outgoing) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(calls.get()).isZero();
        assertThat(response.getContentAsString()).doesNotContain(encoded, "fixture-global-key");
        assertThat(UserContext.getUserId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v2/sessions", "/api/platforms/qa/events"})
    void anonymousAndLegacyCredentialsCannotBypassExplicitCentralMode(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-API-Key", "fixture-global-key");
        request.addParameter("api_key", "fixture-global-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, (incoming, outgoing) -> fail("Unexpected admission"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blankLegacyKeyCannotDisableCentralMode() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setApiKey("");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("central", verifier());
        var filter = new ApiKeyAuthFilter(properties, mock(UserAccessService.class));
        filter.configureCentralVerifier(beans.getBeanProvider(CentralJwtVerifier.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v2/sessions"), response,
                (incoming, outgoing) -> fail("Unexpected development identity"));
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void healthRemainsAvailableWithoutAnOwnerIdentity() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger calls = new AtomicInteger();
        filter().doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response,
                (incoming, outgoing) -> {
                    calls.incrementAndGet();
                    assertThat(UserContext.getUserId()).isNull();
                });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void springActivatesCentralDecoderAndInjectsItIntoExistingFilter() {
        new ApplicationContextRunner().withUserConfiguration(CentralJwtVerifier.class, ApiKeyAuthFilter.class)
                .withBean(AgentProperties.class, AgentProperties::new)
                .withBean(UserAccessService.class, () -> mock(UserAccessService.class))
                .withPropertyValues("agent.central-auth.enabled=true", "agent.central-auth.issuer=" + ISSUER,
                        "agent.central-auth.jwks-uri=" + jwks, "agent.central-auth.audience=sdlc",
                        "agent.central-auth.session-uri=" + sessionUrl)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CentralJwtVerifier.class);
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    context.getBean(ApiKeyAuthFilter.class).doFilter(
                            new MockHttpServletRequest("GET", "/api/v2/sessions"), response,
                            (incoming, outgoing) -> fail("Central mode was not wired"));
                    assertThat(response.getStatus()).isEqualTo(401);
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 302, 503})
    void inactiveUnavailableAndRedirectedSessionCannotReachController(int status) throws Exception {
        sessionStatus.set(status);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer " + token(builder -> {}, key));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, (incoming, outgoing) -> fail("Unexpected admission"));
        assertThat(response.getStatus()).isEqualTo(status == 401 || status == 403 ? 401 : 503);
        assertThat(unexpectedRequests.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "inactive", "malformed", "oversized"})
    void sessionReadbackMustConfirmExactActiveIdentityWithinResponseLimit(String failure) throws Exception {
        sessionReply.set(switch (failure) {
            case "foreign" -> "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"active\"}";
            case "inactive" -> "{\"id\":\"" + SUBJECT + "\",\"status\":\"disabled\"}";
            case "oversized" -> "x".repeat(65537);
            default -> "broken-json";
        });
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer " + token(builder -> {}, key));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter().doFilter(request, response, (incoming, outgoing) -> fail("Unexpected admission"));
        assertThat(response.getStatus()).isEqualTo(failure.equals("foreign") || failure.equals("inactive") ? 401 : 503);
    }
}
