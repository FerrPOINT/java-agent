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
    private final ObjectMapper mapper = new ObjectMapper();

    /** Rejects incomplete deployment configuration before authenticated requests are served. */
    @PostConstruct
    public void init() {
        String issuer = environment.getRequiredProperty("agent.central-auth.issuer");
        String jwksUri = environment.getRequiredProperty("agent.central-auth.jwks-uri");
        String audience = environment.getRequiredProperty("agent.central-auth.audience");
        String sessionUrl = environment.getRequiredProperty("agent.central-auth.session-uri");
        if (issuer == null || issuer.isBlank() || jwksUri == null || jwksUri.isBlank()
                || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Central authentication requires deployment issuer/JWKS/audience");
        }
        sessionUri = URI.create(sessionUrl);
        if (!sessionUri.isAbsolute() || sessionUri.getHost() == null || sessionUri.getUserInfo() != null
                || sessionUri.getQuery() != null || sessionUri.getFragment() != null
                || !("http".equals(sessionUri.getScheme()) || "https".equals(sessionUri.getScheme()))) {
            throw new IllegalArgumentException("Invalid central session endpoint");
        }
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

    /** Returns only verified identity; tokens/provider errors are never logged. */
    public String verifySubject(String bearerToken) throws JwtException {
        if (bearerToken == null || bearerToken.isBlank() || bearerToken.length() > 16384) {
            throw new JwtException("Central bearer token required");
        }
        final String subject;
        try {
            subject = decoder.decode(bearerToken).getSubject();
        } catch (BadJwtException invalid) {
            throw invalid;
        } catch (JwtException unavailable) {
            throw new CentralAuthUnavailableException();
        }
        confirmActiveSession(bearerToken, subject);
        return subject;
    }

    private void confirmActiveSession(String token, String subject) {
        HttpRequest request = HttpRequest.newBuilder(sessionUri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        var pending = sessionClient.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
        try {
            HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new BadJwtException("Central session is not active");
            }
            if (response.statusCode() != 200) throw new CentralAuthUnavailableException();
            var user = mapper.readTree(response.body());
            if (user == null || !subject.equals(user.path("id").asText())
                    || !"active".equals(user.path("status").asText())) {
                throw new BadJwtException("Central identity binding is not active");
            }
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new CentralAuthUnavailableException();
        } catch (IOException | ExecutionException | TimeoutException unavailable) {
            pending.cancel(true);
            throw new CentralAuthUnavailableException();
        }
    }

    public static final class CentralAuthUnavailableException extends JwtException {
        private static final long serialVersionUID = 1L;
        public CentralAuthUnavailableException() { super("Central authentication unavailable"); }
    }
}
