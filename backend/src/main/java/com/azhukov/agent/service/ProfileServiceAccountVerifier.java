package com.azhukov.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class ProfileServiceAccountVerifier {
    private final ObjectMapper json;
    private final String introspectionEndpoint;
    private final HttpClient http = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public ProfileServiceAccountVerifier(ObjectMapper json,
            @Value("${agent.central-auth.personal-token-uri:}") String introspectionEndpoint) {
        this.json = json;
        this.introspectionEndpoint = introspectionEndpoint;
    }

    public String verify(String token, Set<String> expectedScopes) {
        return verify(token, expectedScopes, Set.of());
    }

    public String verify(String token, Set<String> requiredScopes, Set<String> optionalScopes) {
        try {
            URI endpoint = URI.create(introspectionEndpoint);
            if (!trustedHttpEndpoint(endpoint) || !"/auth/tokens/introspect".equals(endpoint.getRawPath())
                || !token.matches("(?:pdlc_pat_|sdlc_pat_)[a-zA-Z0-9_-]+")) {
                throw new IllegalStateException();
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache, no-store")
                .header("Authorization", "Bearer " + token).GET().build();
            Instant started = Instant.now();
            var response = http.send(request, HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), 16_384));
            if (response.statusCode() != 200
                || !"identity".equals(response.headers().firstValue("Content-Encoding").orElse("identity"))) {
                throw new IllegalStateException();
            }
            var proof = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .readTree(response.body());
            if (proof == null || !proof.isObject() || !"agent".equals(proof.path("subject_kind").asText())
                || !"service_account".equals(proof.path("subject_role").asText())
                || !proof.path("email").isTextual() || proof.path("email").asText().isBlank()
                || !proof.path("display_name").isTextual()
                || !proof.path("scopes").isArray() || !proof.path("observed_at").isTextual()
                || !proof.has("expires_at")) {
                throw new IllegalStateException();
            }
            var scopes = new HashSet<String>();
            for (var scope : proof.path("scopes")) {
                if (!scope.isTextual() || !scopes.add(scope.asText())) {
                    throw new IllegalStateException();
                }
            }
            if (!scopes.containsAll(requiredScopes)
                || scopes.stream().anyMatch(scope -> !requiredScopes.contains(scope) && !optionalScopes.contains(scope))) {
                throw new IllegalStateException();
            }
            String subject = proof.path("sub").asText();
            UUID uuid = UUID.fromString(subject);
            if (!uuid.toString().equals(subject) || uuid.equals(new UUID(0, 0))) {
                throw new IllegalStateException();
            }
            Instant now = Instant.now();
            Instant observed = Instant.parse(proof.path("observed_at").asText());
            if (observed.isBefore(started.minusSeconds(1)) || observed.isAfter(now.plusSeconds(1))
                || (!proof.path("expires_at").isNull()
                    && !Instant.parse(proof.path("expires_at").asText()).isAfter(now))) {
                throw new IllegalStateException();
            }
            return subject;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException | DateTimeParseException | IllegalStateException e) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Profile service identity is unavailable");
    }

    public static boolean trustedHttpEndpoint(URI uri) {
        return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
            && uri.getRawFragment() == null && uri.equals(uri.normalize());
    }
}
