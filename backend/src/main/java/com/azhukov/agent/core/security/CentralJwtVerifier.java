package com.azhukov.agent.core.security;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.List;
import com.pdlc.auth.HumanApplicationAccess;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Central bearer identity keeps the gateway's global API key privileges out of
 * human requests; deployment selects this mode explicitly and cannot fall back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "agent.central-auth", name = "enabled", havingValue = "true")
public final class CentralJwtVerifier {
    private final Environment environment;
    private JwtDecoder decoder;
    private HttpClient sessionClient;
    private URI sessionUri;
    private URI personalTokenUri;
    private String issuer;
    private boolean requireApplicationProof;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Rejects incomplete deployment configuration before authenticated requests are served. */
    @PostConstruct
    public void init() {
        issuer = environment.getRequiredProperty("agent.central-auth.issuer");
        String jwksUri = environment.getRequiredProperty("agent.central-auth.jwks-uri");
        String audience = environment.getRequiredProperty("agent.central-auth.audience");
        String sessionUrl = environment.getRequiredProperty("agent.central-auth.session-uri");
        if (issuer == null || issuer.isBlank() || jwksUri == null || jwksUri.isBlank()
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Central authentication requires deployment issuer/JWKS/audience");
        }
        sessionUri = endpoint(sessionUrl);
        String personalUrl = environment.getProperty("agent.central-auth.personal-token-uri", "");
        personalTokenUri = personalUrl.isBlank() ? null : endpoint(personalUrl);
        requireApplicationProof = environment.getProperty(
                "agent.central-auth.application-access-required", Boolean.class, false);
        sessionClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256).build();
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer),
                token -> {
                    if (token.getAudience() != null && token.getAudience().contains(audience)
                            && validSubject(token.getSubject()) && validSubject(token.getClaimAsString("sid"))
                            && token.getExpiresAt() != null
                            && token.getIssuedAt() != null && !token.getIssuedAt().isAfter(Instant.now())
                            && token.getExpiresAt().isAfter(token.getIssuedAt())) {
                        return OAuth2TokenValidatorResult.success();
                    }
                    return OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Invalid central identity binding", null));
                }));
        decoder = jwtDecoder;
    }

    private static boolean validSubject(String subject) {
        if (subject == null || subject.length() != 36) return false;
        try {
            UUID id = UUID.fromString(subject);
            return !id.equals(new UUID(0, 0)) && id.toString().equals(subject);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid central identity endpoint");
        }
        return uri;
    }

    /** The proof and role come from the same live Auth confirmation, never from JWT role claims. */
    public HumanApplicationAccess verifyAccess(String bearerToken, String method) throws JwtException {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > 16384) {
            throw new BadJwtException("Central bearer token required");
        }
        long started = System.nanoTime();
        Instant wallStarted = Instant.now();
        boolean personal = bearerToken.startsWith("sdlc_pat_") || bearerToken.startsWith("pdlc_pat_");
        String subject = null;
        Instant tokenExpiry = null;
        if (!personal) {
            try {
                var jwt = decoder.decode(bearerToken);
                subject = jwt.getSubject();
                tokenExpiry = jwt.getExpiresAt();
            } catch (BadJwtException invalid) {
                throw invalid;
            } catch (JwtException unavailable) {
                throw new CentralAuthUnavailableException();
            }
        } else if (personalTokenUri == null) {
            throw new CentralAuthUnavailableException();
        }
        Map<String, Object> user = confirmIdentity(bearerToken, personal ? personalTokenUri : sessionUri);
        if (personal) {
            Object owner = user.get("sub");
            if (!(owner instanceof String id) || !validSubject(id)) {
                throw new BadJwtException("Invalid personal token owner");
            }
            subject = id;
            Object scopes = user.get("scopes");
            String required = Set.of("GET", "HEAD", "OPTIONS").contains(method) ? "read" : "write";
            if (!(scopes instanceof List<?> values) || !values.contains("java-agent:" + required)) {
                throw new CentralAccessDeniedException();
            }
            Object expires = user.get("expires_at");
            if (!(expires instanceof String text)) throw new CentralAuthUnavailableException();
            try { tokenExpiry = java.time.OffsetDateTime.parse(text).toInstant(); }
            catch (java.time.DateTimeException malformed) { throw new CentralAuthUnavailableException(); }
        } else if (!subject.equals(user.get("id")) || !"active".equals(user.get("status"))) {
            throw new BadJwtException("Central identity binding is not active");
        }
        try {
            var proof = HumanApplicationAccess.confirm(user, issuer, subject, started, wallStarted,
                    tokenExpiry, requireApplicationProof);
            if (!proof.permits(Set.of("java-agent"), false)) throw new CentralAccessDeniedException();
            return proof;
        } catch (HumanApplicationAccess.AdmissionDeniedException denied) {
            throw new CentralAccessDeniedException();
        } catch (IllegalArgumentException malformed) {
            throw new CentralAuthUnavailableException();
        }
    }

    /** Compatibility identity read keeps the same admission boundary as a normal request. */
    public String verifySubject(String bearerToken) throws JwtException {
        return verifyAccess(bearerToken, "GET").subject();
    }

    private Map<String, Object> confirmIdentity(String token, URI endpoint) {
        // Auth includes the original PAT lifetime only on this explicit bounded readback.
        URI target = endpoint.equals(personalTokenUri) ? URI.create(endpoint + "?include_lifetime=true") : endpoint;
        HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        var pending = sessionClient.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
        try {
            HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BadJwtException("Central identity is not active");
            }
            if (response.statusCode() != 200) throw new CentralAuthUnavailableException();
            Map<String, Object> identity = mapper.readValue(response.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            if (identity == null) throw new CentralAuthUnavailableException();
            return identity;
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new CentralAuthUnavailableException();
        } catch (IOException | ExecutionException | TimeoutException unavailable) {
            pending.cancel(true);
            throw new CentralAuthUnavailableException();
        }
    }

    /** Separates a valid identity with denied app access from an invalid bearer token. */
    public static final class CentralAccessDeniedException extends JwtException {
        private static final long serialVersionUID = 1L;
        public CentralAccessDeniedException() { super("Current application access required"); }
    }

    public static final class CentralAuthUnavailableException extends JwtException {
        private static final long serialVersionUID = 1L;
        public CentralAuthUnavailableException() { super("Central authentication unavailable"); }
    }
}
